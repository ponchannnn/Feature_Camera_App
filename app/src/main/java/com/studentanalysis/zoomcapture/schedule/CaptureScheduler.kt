package com.studentanalysis.zoomcapture.schedule

import android.os.SystemClock
import android.util.Log
import com.studentanalysis.zoomcapture.camera.CameraController
import com.studentanalysis.zoomcapture.camera.PresetShooter
import com.studentanalysis.zoomcapture.camera.strategy.CaptureOutcome
import com.studentanalysis.zoomcapture.monitor.DeviceMonitor
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.preset.PresetSet
import com.studentanalysis.zoomcapture.storage.CaptureLogRow
import com.studentanalysis.zoomcapture.storage.CaptureLogWriter
import com.studentanalysis.zoomcapture.storage.ImageSaver
import com.studentanalysis.zoomcapture.storage.SessionInfo
import com.studentanalysis.zoomcapture.storage.SessionWriter
import com.studentanalysis.zoomcapture.zoom.ZoomPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StopReason(val label: String) {
    MANUAL("手動"),
    LOW_STORAGE("容量不足"),
    ERROR("エラー"),
}

data class CaptureProgress(
    val running: Boolean = false,
    val startElapsedMs: Long? = null,
    val shots: Long = 0,
    val ok: Long = 0,
    val late: Long = 0,
    val skipped: Long = 0,
    val errors: Long = 0,
    val currentPresetId: String? = null,
    /** プリセット ID → 最後に保存した JPEG */
    val lastFiles: Map<String, String> = emptyMap(),
    val bytesWritten: Long = 0,
    val sessionDir: String? = null,
    val stopReason: StopReason? = null,
    val message: String? = null,
    val thermalWarning: Boolean = false,
)

/**
 * ラウンドロビンの自動撮影。予定時刻(SlotPlanner)に合わせてプリセットを切り替えて撮影し、
 * 画像の保存とログの追記は別スレッドで行う(撮影のタイミングを止めないため)。
 */
class CaptureScheduler(
    private val controller: CameraController,
    private val set: PresetSet,
    private val plans: Map<String, ZoomPlan>,
    private val jpegOrientation: Int,
    private val sessionDir: File,
    private val sessionWriter: SessionWriter,
    private val initialInfo: SessionInfo,
    private val monitor: DeviceMonitor,
) {
    private val presets: List<Preset> = set.enabledPresets
    private val shooter = PresetShooter(controller, jpegOrientation)
    private val saver = ImageSaver()
    private val config = ScheduleConfig(set.periodSec, set.stepSec)

    private val _progress = MutableStateFlow(CaptureProgress(sessionDir = sessionDir.path))
    val progress: StateFlow<CaptureProgress> = _progress.asStateFlow()

    @Volatile
    private var stopReasonRequested: StopReason? = null

    @Volatile
    private var stopMessage: String? = null

    fun requestStop(reason: StopReason = StopReason.MANUAL, message: String? = null) {
        if (stopReasonRequested == null) {
            stopReasonRequested = reason
            stopMessage = message
        }
    }

    suspend fun run(): StopReason {
        val log = CaptureLogWriter(File(sessionDir, SessionWriter.CAPTURES_CSV))
        val warnings = mutableListOf<String>()
        var reason = StopReason.ERROR
        var shots = 0L
        _progress.update { it.copy(running = true) }
        try {
            reason = coroutineScope {
                val writer = Dispatchers.IO.limitedParallelism(1)

                // 最初のプリセットで露出とホワイトバランスを合わせてから、ホワイトバランスをロックする
                val first = presets.first()
                shooter.prepare(first, plans.getValue(first.id), awbLock = false, SystemClock.elapsedRealtime() + 3_000)
                val t0 = SystemClock.elapsedRealtime() + START_DELAY_MS
                val wallT0 = System.currentTimeMillis() + START_DELAY_MS
                _progress.update { it.copy(startElapsedMs = t0) }

                var consecutiveErrors = 0
                var thermalWarned = false
                for (slot in SlotPlanner.slots(presets.size, config.periodMs, config.stepMs)) {
                    stopReasonRequested?.let { return@coroutineScope it }

                    val status = monitor.current()
                    if (status.freeBytes < MIN_FREE_BYTES) return@coroutineScope StopReason.LOW_STORAGE
                    if (status.thermalSevere && !thermalWarned) {
                        thermalWarned = true
                        warnings += "${timestamp(System.currentTimeMillis())} 端末の温度が非常に高くなりました (${DeviceMonitor.thermalLabel(status.thermalStatus)})"
                        _progress.update { it.copy(thermalWarning = true) }
                    }

                    val preset = presets[slot.index]
                    val plan = plans.getValue(preset.id)
                    val seq = ++shots
                    val dueAt = t0 + slot.offsetMs
                    _progress.update { it.copy(currentPresetId = preset.id, shots = seq) }

                    val params = shooter.prepare(preset, plan, awbLock = true, deadlineElapsedMs = dueAt)
                    val wait = dueAt - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)

                    val lateness = SystemClock.elapsedRealtime() - dueAt
                    val baseRow = CaptureLogRow(
                        seq = seq, cycle = slot.cycle, presetId = preset.id, scheduledMs = slot.offsetMs,
                        capturedMs = null, wallTime = null, status = "", captureMode = preset.captureMode.logName,
                        physicalCameraId = null, zoomRatio = plan.zoomRatio, crop = null, fov = plan.fittedRect,
                        imageSize = null, focusDistance = null, exposureTimeNs = null, iso = null,
                        afState = null, aeState = null, file = null,
                    )
                    if (lateness >= config.stepMs) {
                        // 次の予定時刻まで来てしまったので、この撮影は飛ばす
                        launch(writer) { log.append(baseRow.copy(status = "skipped")) }
                        _progress.update { it.copy(skipped = it.skipped + 1) }
                        continue
                    }

                    val outcome = try {
                        shooter.shoot(preset, params, set.jpegQuality)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "撮影に失敗: ${preset.id}", e)
                        launch(writer) { log.append(baseRow.copy(status = "error")) }
                        _progress.update { it.copy(errors = it.errors + 1, message = e.message) }
                        if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            stopMessage = "撮影の失敗が $MAX_CONSECUTIVE_ERRORS 回続いたため停止しました: ${e.message}"
                            return@coroutineScope StopReason.ERROR
                        }
                        continue
                    }
                    consecutiveErrors = 0

                    val capturedMs = capturedMs(outcome, t0)
                    val wallMs = wallT0 + capturedMs
                    val statusLabel = if (lateness > LATE_TOLERANCE_MS) "late" else "ok"
                    _progress.update {
                        if (statusLabel == "late") it.copy(late = it.late + 1) else it.copy(ok = it.ok + 1)
                    }
                    launch(writer) {
                        save(log, preset, outcome, baseRow.copy(capturedMs = capturedMs, status = statusLabel), wallMs)
                    }
                }
                StopReason.MANUAL
            }
        } catch (e: CancellationException) {
            reason = stopReasonRequested ?: StopReason.ERROR
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "自動撮影が異常終了", e)
            reason = stopReasonRequested ?: StopReason.ERROR
            if (stopMessage == null) stopMessage = e.message
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { log.close() }
                val finalReason = stopReasonRequested ?: reason
                sessionWriter.writeInfo(
                    sessionDir,
                    initialInfo.copy(
                        endWallTime = timestamp(System.currentTimeMillis()),
                        stopReason = finalReason.name + (stopMessage?.let { ": $it" } ?: ""),
                        warnings = initialInfo.warnings + warnings,
                        shotCount = shots,
                    ),
                )
                _progress.update {
                    it.copy(running = false, stopReason = finalReason, message = stopMessage ?: it.message)
                }
            }
        }
        return stopReasonRequested ?: reason
    }

    private fun save(log: CaptureLogWriter, preset: Preset, outcome: CaptureOutcome, row: CaptureLogRow, wallMs: Long) {
        val dir = sessionWriter.presetDir(sessionDir, preset)
        val baseName = "%06d_%s_%s".format(row.seq, preset.id, FILE_TIME.get()!!.format(Date(wallMs)))
        val s = outcome.summary
        var bytes = 0L
        var lastJpeg: String? = null
        for (file in outcome.files) {
            val saved = runCatching { saver.save(dir, baseName, file, wallMs, preset.id) }
            val out = saved.getOrNull()
            if (out != null) {
                bytes += out.length()
                if (file.extension == "jpg") lastJpeg = out.path
            }
            log.append(
                row.copy(
                    wallTime = timestamp(wallMs),
                    status = if (out == null) "error" else row.status,
                    physicalCameraId = s.physicalCameraId,
                    zoomRatio = s.zoomRatio ?: row.zoomRatio,
                    crop = s.cropRegion,
                    imageSize = outcome.imageSize,
                    focusDistance = s.focusDistance,
                    exposureTimeNs = s.exposureTimeNs,
                    iso = s.iso,
                    afState = s.afState,
                    aeState = s.aeState,
                    file = out?.relativeTo(sessionDir)?.invariantSeparatorsPath,
                ),
            )
        }
        _progress.update {
            it.copy(
                bytesWritten = it.bytesWritten + bytes,
                lastFiles = if (lastJpeg != null) it.lastFiles + (preset.id to lastJpeg) else it.lastFiles,
            )
        }
    }

    /** センサーのタイムスタンプが elapsedRealtime と同じ時間軸ならそれを使い、違えば今の時刻で代用する */
    private fun capturedMs(outcome: CaptureOutcome, t0: Long): Long {
        val ts = outcome.summary.sensorTimestampNs
        val elapsed = if (controller.cap.timestampRealtime && ts != null) ts / 1_000_000 else SystemClock.elapsedRealtime()
        return elapsed - t0
    }

    companion object {
        private const val TAG = "CaptureScheduler"
        private const val START_DELAY_MS = 500L

        /** これ以上遅れたら status を late にする */
        const val LATE_TOLERANCE_MS = 150L
        const val MIN_FREE_BYTES = 1L shl 30
        const val MAX_CONSECUTIVE_ERRORS = 5

        private val FILE_TIME = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT) }
        private val ISO_TIME = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT) }

        fun timestamp(wallMs: Long): String = ISO_TIME.get()!!.format(Date(wallMs))
    }
}
