package com.studentanalysis.zoomcapture.camera.strategy

import com.studentanalysis.zoomcapture.camera.OutputKind
import com.studentanalysis.zoomcapture.camera.ShotParams
import com.studentanalysis.zoomcapture.camera.summary
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.storage.CapturedFile

/** 標準: JPEG を1枚撮る。すべての端末で使える */
object StandardJpegStrategy : CaptureStrategy {
    override val mode = CaptureMode.STANDARD

    override suspend fun capture(ctx: CaptureContext, preset: Preset, params: ShotParams): CaptureOutcome {
        val c = ctx.controller
        c.drainImages(OutputKind.JPEG)
        val request = c.buildStill(params, listOf(OutputKind.JPEG), ctx.jpegQuality, ctx.jpegOrientation)
        val result = c.capture(listOf(request)).single()
        val image = c.awaitImages(OutputKind.JPEG, 1).single()
        val (bytes, size) = image.use { it.jpegBytes() to SizeI(it.width, it.height) }
        return CaptureOutcome(result.summary(), listOf(CapturedFile("", "jpg", bytes)), size)
    }
}
