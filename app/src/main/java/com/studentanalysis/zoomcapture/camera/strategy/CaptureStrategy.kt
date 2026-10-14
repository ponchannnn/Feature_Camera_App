package com.studentanalysis.zoomcapture.camera.strategy

import android.media.Image
import com.studentanalysis.zoomcapture.camera.CameraController
import com.studentanalysis.zoomcapture.camera.ResultSummary
import com.studentanalysis.zoomcapture.camera.ShotParams
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.storage.CapturedFile

class CaptureContext(
    val controller: CameraController,
    val jpegQuality: Int,
    /** JPEG_ORIENTATION に指定する値(画面上で正立させるための回転) */
    val jpegOrientation: Int,
)

/** 撮影1回の結果。files は保存前のデータ */
class CaptureOutcome(
    val summary: ResultSummary,
    val files: List<CapturedFile>,
    val imageSize: SizeI,
)

/** 撮影方式。端末で使えるかの判定と、1回の撮影を受け持つ */
interface CaptureStrategy {
    val mode: CaptureMode

    fun isSupported(cap: CapabilityReport): Boolean = cap.supports(mode)

    suspend fun capture(ctx: CaptureContext, preset: Preset, params: ShotParams): CaptureOutcome
}

object CaptureStrategies {
    private val all: Map<CaptureMode, CaptureStrategy> = listOf(
        StandardJpegStrategy,
        MaxResolutionStrategy,
        RawStrategy,
        BurstStrategy,
    ).associateBy { it.mode }

    fun forMode(mode: CaptureMode): CaptureStrategy = all.getValue(mode)
}

internal fun Image.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}
