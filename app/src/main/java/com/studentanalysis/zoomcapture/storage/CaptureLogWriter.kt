package com.studentanalysis.zoomcapture.storage

import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.zoom.NormRect
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/** captures.csv の1行。撮影1回につき通し番号は1つで、RAW や連写では1回の撮影で複数行になる */
data class CaptureLogRow(
    val seq: Long,
    val cycle: Long,
    val presetId: String,
    val scheduledMs: Long,
    val capturedMs: Long?,
    val wallTime: String?,
    val status: String,
    val captureMode: String,
    val physicalCameraId: String?,
    val zoomRatio: Float?,
    val crop: RectI?,
    val fov: NormRect,
    val imageSize: SizeI?,
    val focusDistance: Float?,
    val exposureTimeNs: Long?,
    val iso: Int?,
    val afState: String?,
    val aeState: String?,
    val file: String?,
)

object CaptureLogFormat {

    val HEADER = listOf(
        "seq", "cycle", "preset_id", "scheduled_ms", "captured_ms", "wall_time", "status", "capture_mode",
        "physical_camera_id", "zoom_ratio", "crop_x", "crop_y", "crop_w", "crop_h",
        "fov_x", "fov_y", "fov_w", "fov_h", "image_w", "image_h",
        "focus_distance", "exposure_time_ns", "iso", "af_state", "ae_state", "file",
    )

    fun toCsvLine(row: CaptureLogRow): String = listOf(
        row.seq, row.cycle, row.presetId, row.scheduledMs, row.capturedMs, row.wallTime, row.status, row.captureMode,
        row.physicalCameraId, row.zoomRatio?.let { "%.4f".format(java.util.Locale.ROOT, it) },
        row.crop?.left, row.crop?.top, row.crop?.width, row.crop?.height,
        f(row.fov.left), f(row.fov.top), f(row.fov.width), f(row.fov.height),
        row.imageSize?.width, row.imageSize?.height,
        row.focusDistance?.let { "%.4f".format(java.util.Locale.ROOT, it) },
        row.exposureTimeNs, row.iso, row.afState, row.aeState, row.file,
    ).joinToString(",") { escape(it?.toString() ?: "") }

    private fun f(v: Float) = "%.5f".format(java.util.Locale.ROOT, v)

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** captures.csv に1行ずつ追記する。途中でアプリが落ちても、それまでの行は残る */
class CaptureLogWriter(file: File) : Closeable {

    private val writer: BufferedWriter

    init {
        val isNew = !file.exists() || file.length() == 0L
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        if (isNew) {
            writer.write(CaptureLogFormat.HEADER.joinToString(","))
            writer.newLine()
            writer.flush()
        }
    }

    @Synchronized
    fun append(row: CaptureLogRow) {
        writer.write(CaptureLogFormat.toCsvLine(row))
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() {
        writer.close()
    }
}
