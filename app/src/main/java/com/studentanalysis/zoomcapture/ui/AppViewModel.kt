package com.studentanalysis.zoomcapture.ui

import android.app.Application
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studentanalysis.zoomcapture.BuildConfig
import com.studentanalysis.zoomcapture.camera.CameraController
import com.studentanalysis.zoomcapture.camera.PresetShooter
import com.studentanalysis.zoomcapture.capability.CapabilityProbe
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.monitor.DeviceMonitor
import com.studentanalysis.zoomcapture.monitor.DeviceStatus
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.DefaultPresets
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.preset.PresetRepository
import com.studentanalysis.zoomcapture.preset.PresetSet
import com.studentanalysis.zoomcapture.preset.zoomPlan
import com.studentanalysis.zoomcapture.schedule.CaptureProgress
import com.studentanalysis.zoomcapture.schedule.CaptureScheduler
import com.studentanalysis.zoomcapture.schedule.ScheduleCheck
import com.studentanalysis.zoomcapture.schedule.ScheduleConfig
import com.studentanalysis.zoomcapture.schedule.ScheduleValidator
import com.studentanalysis.zoomcapture.schedule.StopReason
import com.studentanalysis.zoomcapture.storage.OutputPixels
import com.studentanalysis.zoomcapture.storage.PresetRecord
import com.studentanalysis.zoomcapture.storage.SessionInfo
import com.studentanalysis.zoomcapture.storage.SessionWriter
import com.studentanalysis.zoomcapture.storage.ShotSpec
import com.studentanalysis.zoomcapture.storage.StorageEstimator
import com.studentanalysis.zoomcapture.upload.HealthInfo
import com.studentanalysis.zoomcapture.upload.ServerConfig
import com.studentanalysis.zoomcapture.upload.SessionUploadState
import com.studentanalysis.zoomcapture.upload.StorageMode
import com.studentanalysis.zoomcapture.upload.UploadManager
import com.studentanalysis.zoomcapture.upload.UploadStatus
import com.studentanalysis.zoomcapture.zoom.CoordinateMapper
import com.studentanalysis.zoomcapture.zoom.NormRect
import com.studentanalysis.zoomcapture.zoom.ZoomPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

enum class Screen { HOME, EDITOR, CAPTURE, SESSIONS, DEVICE_INFO, SERVER }

sealed interface CameraState {
    data object Closed : CameraState
    data object Opening : CameraState
    data object Ready : CameraState
    data class Error(val message: String) : CameraState
}

data class TestShotState(
    val running: Boolean = false,
    val presetId: String? = null,
    val imagePath: String? = null,
    /** 同じパスに上書きするので、画像の読み直しに使う */
    val version: Long = 0,
    val latencyMs: Long? = null,
    val message: String? = null,
)

data class StorageEstimate(
    val bytesPerSecond: Double,
    val bytesPerLecture: Long,
    val freeBytes: Long,
    val secondsRemaining: Double,
)

data class SessionSummary(
    val dir: File,
    val info: SessionInfo?,
    val rows: Int,
    val sizeBytes: Long,
    val upload: SessionUploadState,
)

/** 画面の状態と操作をまとめる。撮影やスケジュールのロジックは camera/ schedule/ に任せる */
class AppViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repo = PresetRepository(app)
    private val sessionsRoot = SessionWriter.sessionsRoot(app)
    private val sessionWriter = SessionWriter(sessionsRoot)
    val monitor = DeviceMonitor(app, sessionsRoot)
    private val uploads = UploadManager.get(app)
    val uploadStatus: StateFlow<UploadStatus> = uploads.status

    val screen = MutableStateFlow(Screen.HOME)

    val capability = MutableStateFlow<CapabilityReport?>(null)
    val capabilityError = MutableStateFlow<String?>(null)

    val sets = MutableStateFlow<List<PresetSet>>(emptyList())
    val selectedName = MutableStateFlow<String?>(null)
    val selectedSet: StateFlow<PresetSet?> = combine(sets, selectedName) { list, name ->
        list.firstOrNull { it.name == name } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val testShot = MutableStateFlow(TestShotState())
    val captureProgress = MutableStateFlow(CaptureProgress())
    val dimmed = MutableStateFlow(false)
    val deviceStatus: StateFlow<DeviceStatus> =
        monitor.statusFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), monitor.current())

    /** 画面の回転(度)。Activity から設定する(大画面の端末では横向き固定が無視されることがあるため) */
    val displayRotationDegrees = MutableStateFlow(90)

    private var controller: CameraController? = null
    private var previewSurface: Surface? = null
    private var cameraJob: Job? = null
    private var scheduler: CaptureScheduler? = null
    private var captureJob: Job? = null
    private var pendingStart = false

    /** センサーの画像を画面上で正立させるための回転 */
    val rotation: Int
        get() = capability.value?.let { CoordinateMapper.relativeRotation(it.sensorOrientation, displayRotationDegrees.value) } ?: 0

    fun onPermissionGranted() {
        if (capability.value != null) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { CapabilityProbe.probe(app) } }
            result.onSuccess { cap ->
                capability.value = cap
                loadSets(cap)
            }.onFailure { capabilityError.value = it.message ?: it.toString() }
        }
    }

    private fun loadSets(cap: CapabilityReport) {
        var all = repo.loadAll()
        // 前回送り残したものがあれば続きを送る
        uploads.kick()
        uploads.scheduleWorker()
        if (all.isEmpty()) {
            val set = DefaultPresets.create("講義室1", cap.model, cap.zoomMin)
            repo.save(set)
            all = listOf(set)
        }
        sets.value = all
        selectedName.value = repo.selectedName?.takeIf { n -> all.any { it.name == n } } ?: all.first().name
    }

    // ---- プリセットセット ----

    fun selectSet(name: String) {
        selectedName.value = name
        repo.selectedName = name
    }

    fun createSet(name: String): Boolean {
        val cap = capability.value ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || sets.value.any { it.name == trimmed }) return false
        saveSet(DefaultPresets.create(trimmed, cap.model, cap.zoomMin))
        selectSet(trimmed)
        return true
    }

    fun deleteSet(name: String) {
        repo.delete(name)
        sets.value = sets.value.filterNot { it.name == name }
        if (sets.value.isEmpty()) capability.value?.let { loadSets(it) } else selectSet(sets.value.first().name)
    }

    fun updateSchedule(periodSec: Double? = null, stepSec: Double? = null, jpegQuality: Int? = null) {
        val set = selectedSet.value ?: return
        saveSet(
            set.copy(
                periodSec = periodSec ?: set.periodSec,
                stepSec = stepSec ?: set.stepSec,
                jpegQuality = jpegQuality ?: set.jpegQuality,
            ),
        )
    }

    private fun saveSet(set: PresetSet) {
        repo.save(set)
        sets.value = (sets.value.filterNot { it.name == set.name } + set).sortedBy { it.name }
    }

    // ---- プリセット ----

    fun updatePreset(preset: Preset) {
        val set = selectedSet.value ?: return
        saveSet(set.replace(preset))
    }

    fun setTargetRect(presetId: String, rect: NormRect) {
        val preset = selectedSet.value?.presets?.firstOrNull { it.id == presetId } ?: return
        updatePreset(preset.withTargetRect(rect))
    }

    fun addPreset(): String? {
        val set = selectedSet.value ?: return null
        val id = set.nextPresetId()
        saveSet(set.copy(presets = set.presets + Preset(id, "プリセット$id", NormRect(0.25f, 0.25f, 0.75f, 0.75f))))
        return id
    }

    fun deletePreset(id: String) {
        val set = selectedSet.value ?: return
        saveSet(set.copy(presets = set.presets.filterNot { it.id == id }))
    }

    fun movePreset(id: String, delta: Int) {
        val set = selectedSet.value ?: return
        val list = set.presets.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        saveSet(set.copy(presets = list))
    }

    fun planFor(preset: Preset): ZoomPlan? = capability.value?.let { preset.zoomPlan(it.zoomCapability()) }

    // ---- 撮影設定のチェックと容量の見積もり ----

    fun scheduleCheck(set: PresetSet): ScheduleCheck {
        val enabled = set.enabledPresets
        val base = ScheduleValidator.check(ScheduleConfig(set.periodSec, set.stepSec), enabled.map { it.measuredLatencyMs })
        val cap = capability.value ?: return base
        val extra = mutableListOf<String>()
        if (set.deviceModel != cap.model) {
            extra += "このプリセットセットは別の端末 (${set.deviceModel}) で作成されました。撮影範囲がずれる可能性があるので確認してください"
        }
        enabled.filter { !cap.supports(it.captureMode) }.forEach {
            extra += "${it.name}: この端末は「${it.captureMode.label}」に対応していないため、標準 JPEG で撮影します"
        }
        return base.copy(warnings = base.warnings + extra)
    }

    fun storageEstimate(set: PresetSet, status: DeviceStatus): StorageEstimate? {
        val cap = capability.value ?: return null
        val pixels = OutputPixels(
            still = cap.stillSize().pixels,
            maxResolution = cap.maxResolutionStillSize()?.pixels,
            raw = cap.rawSize()?.pixels,
        )
        val specs = set.enabledPresets.map { ShotSpec(it.captureMode, it.burstCount) }
        val perSecond = StorageEstimator.bytesPerSecond(specs, pixels, set.jpegQuality, set.periodSec)
        return StorageEstimate(
            bytesPerSecond = perSecond,
            bytesPerLecture = StorageEstimator.bytesFor(LECTURE_SECONDS, perSecond),
            freeBytes = status.freeBytes,
            secondsRemaining = StorageEstimator.secondsRemaining(status.freeBytes, CaptureScheduler.MIN_FREE_BYTES, perSecond),
        )
    }

    // ---- 画面遷移 ----

    fun navigate(to: Screen) {
        if (captureProgress.value.running && to != Screen.CAPTURE) return
        if (to != Screen.EDITOR) testShot.value = TestShotState()
        screen.value = to
    }

    // ---- カメラ ----

    /** プレビューの Surface ができた / なくなったときに呼ぶ */
    fun onPreviewSurface(surface: Surface?) {
        if (surface == null) {
            closeCamera(cancelPendingStart = true)
            return
        }
        if (surface === previewSurface && controller != null) return
        closeCamera(cancelPendingStart = false)
        val cap = capability.value ?: return
        previewSurface = surface
        cameraState.value = CameraState.Opening
        cameraJob = viewModelScope.launch {
            val c = CameraController(app, cap)
            try {
                c.open(surface, cap.supportedModes().toSet())
                controller = c
                cameraState.value = CameraState.Ready
                onCameraReady(c)
            } catch (e: Exception) {
                c.release()
                cameraState.value = CameraState.Error(e.message ?: e.toString())
            }
        }
    }

    private fun onCameraReady(c: CameraController) {
        when (screen.value) {
            Screen.EDITOR -> runCatching { c.setPreview(PresetShooter(c, rotation).wideParams()) }
            Screen.CAPTURE -> if (pendingStart) beginCapture(c)
            else -> Unit
        }
    }

    /** cancelPendingStart: 「撮影開始」を押してカメラが開くのを待っている状態も取り消すか */
    private fun closeCamera(cancelPendingStart: Boolean) {
        if (cancelPendingStart) pendingStart = false
        if (captureProgress.value.running) {
            scheduler?.requestStop(StopReason.ERROR, "画面が閉じられたため停止しました")
            captureJob?.cancel()
        }
        cameraJob?.cancel()
        controller?.release()
        controller = null
        previewSurface = null
        cameraState.value = CameraState.Closed
    }

    // ---- テスト撮影 ----

    fun testShot(presetId: String, refocus: Boolean = false) {
        val c = controller ?: return
        val cap = capability.value ?: return
        val set = selectedSet.value ?: return
        if (testShot.value.running) return
        var preset = set.presets.firstOrNull { it.id == presetId } ?: return
        if (refocus) preset = preset.copy(fixedFocusDistance = null, fixedFocusPhysicalId = null)
        if (preset.captureMode !in c.enabledModes) {
            testShot.value = TestShotState(presetId = presetId, message = "この端末では「${preset.captureMode.label}」を使えません")
            return
        }
        val shooter = PresetShooter(c, rotation)
        viewModelScope.launch {
            testShot.value = TestShotState(running = true, presetId = presetId)
            try {
                val plan = preset.zoomPlan(cap.zoomCapability())
                val start = SystemClock.elapsedRealtime()
                val params = shooter.prepare(preset, plan, awbLock = false, deadlineElapsedMs = start + 5_000)
                val outcome = shooter.shoot(preset, params, set.jpegQuality)
                val latency = SystemClock.elapsedRealtime() - start

                val jpeg = outcome.files.first { it.extension == "jpg" }
                val file = File(app.cacheDir, "test_${preset.id}.jpg")
                withContext(Dispatchers.IO) { file.writeBytes(jpeg.bytes) }

                var updated = preset.copy(measuredLatencyMs = latency)
                val s = outcome.summary
                if (cap.canFixFocus && updated.fixedFocusDistance == null && s.focusDistance != null) {
                    updated = updated.copy(fixedFocusDistance = s.focusDistance, fixedFocusPhysicalId = s.physicalCameraId)
                }
                updatePreset(updated)

                testShot.value = TestShotState(
                    presetId = presetId,
                    imagePath = file.path,
                    version = System.currentTimeMillis(),
                    latencyMs = latency,
                    message = buildString {
                        append("${outcome.imageSize} / 所要 ${latency}ms")
                        s.physicalCameraId?.let { append(" / レンズ $it") }
                        s.focusDistance?.let { append(" / ピント %.2f".format(it)) }
                        if (s.exposureTimeNs != null && s.iso != null) {
                            append(" / 1/%.0f秒 ISO%d".format(1e9 / s.exposureTimeNs, s.iso))
                        }
                    },
                )
            } catch (e: Exception) {
                testShot.value = TestShotState(presetId = presetId, message = "テスト撮影に失敗しました: ${e.message}")
            } finally {
                runCatching { c.setPreview(shooter.wideParams()) }
            }
        }
    }

    // ---- 自動撮影 ----

    fun startCapture() {
        val set = selectedSet.value ?: return
        if (!scheduleCheck(set).ok) return
        captureProgress.value = CaptureProgress()
        pendingStart = true
        screen.value = Screen.CAPTURE
        controller?.let { if (cameraState.value == CameraState.Ready) beginCapture(it) }
    }

    fun stopCapture() {
        scheduler?.requestStop(StopReason.MANUAL)
    }

    private fun beginCapture(c: CameraController) {
        pendingStart = false
        val cap = capability.value ?: return
        val original = selectedSet.value ?: return

        // セッションで使えない撮影方式は標準 JPEG にする
        val warnings = mutableListOf<String>()
        val set = original.copy(
            presets = original.presets.map {
                if (it.enabled && it.captureMode !in c.enabledModes) {
                    warnings += "${it.id}: ${it.captureMode.label} を使えないため標準 JPEG で撮影"
                    it.copy(captureMode = CaptureMode.STANDARD)
                } else {
                    it
                }
            },
        )
        val zoomCap = cap.zoomCapability()
        val plans = set.enabledPresets.associate { it.id to it.zoomPlan(zoomCap) }

        // サーバーが未設定なら、サーバーに送るモードでも端末にだけ保存する
        val storageMode = uploads.mode.takeIf { !it.usesServer || uploadStatus.value.server != null } ?: StorageMode.LOCAL_ONLY
        if (storageMode != uploads.mode) warnings += "サーバーが設定されていないため、端末にだけ保存しました"

        val start = Date()
        val dir = sessionWriter.createSessionDir(set.name, start)
        val info = SessionInfo(
            appVersion = BuildConfig.VERSION_NAME,
            presetSetName = set.name,
            capability = cap,
            storageMode = storageMode.name,
            serverDevice = uploadStatus.value.server?.device?.takeIf { storageMode.usesServer },
            startWallTime = CaptureScheduler.timestamp(start.time),
            periodSec = set.periodSec,
            stepSec = set.stepSec,
            jpegQuality = set.jpegQuality,
            stillSize = cap.stillSize(),
            presets = set.enabledPresets.map { PresetRecord(it, plans.getValue(it.id)) },
            warnings = warnings,
        )
        sessionWriter.writeInfo(dir, info)

        val s = CaptureScheduler(c, set, plans, rotation, dir, sessionWriter, info, monitor)
        scheduler = s
        if (storageMode.usesServer) uploads.setLiveSession(dir)
        captureJob = viewModelScope.launch {
            val progressJob = launch {
                s.progress.collect {
                    captureProgress.value = it
                    if (storageMode.usesServer) {
                        uploads.onCaptureProgress(
                            SystemClock.elapsedRealtime(), it.shots, it.late + it.skipped + it.errors,
                            deviceStatus.value.thermalSevere,
                        )
                    }
                }
            }
            try {
                s.run()
            } finally {
                captureProgress.value = s.progress.value
                progressJob.cancel()
                scheduler = null
                // session.json に終了時刻を書き終えてから、送信側に撮影の終了を知らせる
                if (storageMode.usesServer) uploads.setLiveSession(null)
            }
        }
    }

    fun setDimmed(value: Boolean) {
        dimmed.value = value
    }

    // ---- セッション一覧 ----

    val sessionsRootPath: String get() = sessionsRoot.path

    suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        sessionsRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedByDescending { it.name }
            .map { dir ->
                val csv = File(dir, SessionWriter.CAPTURES_CSV)
                SessionSummary(
                    dir = dir,
                    info = SessionWriter.readInfo(dir),
                    rows = if (csv.exists()) (csv.useLines { it.count() } - 1).coerceAtLeast(0) else 0,
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    upload = uploads.stateOf(dir),
                )
            }
    }

    suspend fun deleteSession(dir: File) = withContext(Dispatchers.IO) {
        if (dir.parentFile == sessionsRoot) {
            dir.deleteRecursively()
            uploads.onSessionDeleted(dir)
        }
    }

    fun requestUpload(dir: File) = uploads.requestUpload(dir)

    // ---- 保存先・サーバー ----

    fun setStorageMode(mode: StorageMode) = uploads.setMode(mode)

    fun saveServer(config: ServerConfig) = uploads.saveServer(config)

    fun clearServer() = uploads.clearServer()

    suspend fun testConnection(config: ServerConfig): Result<HealthInfo> = uploads.testConnection(config)

    override fun onCleared() {
        closeCamera(cancelPendingStart = true)
        super.onCleared()
    }

    companion object {
        /** 容量の見積もりに使う講義の長さ(100 分) */
        const val LECTURE_SECONDS = 100 * 60.0
    }
}
