package com.studentanalysis.zoomcapture.camera.strategy

import com.studentanalysis.zoomcapture.camera.OutputKind
import com.studentanalysis.zoomcapture.camera.ShotParams
import com.studentanalysis.zoomcapture.camera.summary
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.storage.CapturedFile

/**
 * センサーの最大解像度モード: 高画素センサー(50MP など)の画素をまとめずに出力する。
 * 切り出しは最大解像度の有効画素領域の座標系で指定する(ZoomPlanner.planMaxResolution)。
 */
object MaxResolutionStrategy : CaptureStrategy {
    override val mode = CaptureMode.MAX_RESOLUTION

    override suspend fun capture(ctx: CaptureContext, preset: Preset, params: ShotParams): CaptureOutcome {
        require(params.maxResolutionCrop != null) { "最大解像度モードの切り出し領域がありません" }
        val c = ctx.controller
        c.drainImages(OutputKind.MAX_RESOLUTION_JPEG)
        val request = c.buildStill(
            params, listOf(OutputKind.MAX_RESOLUTION_JPEG), ctx.jpegQuality, ctx.jpegOrientation, maxResolution = true,
        )
        val result = c.capture(listOf(request)).single()
        val image = c.awaitImages(OutputKind.MAX_RESOLUTION_JPEG, 1, timeoutMs = 10_000).single()
        val (bytes, size) = image.use { it.jpegBytes() to SizeI(it.width, it.height) }
        return CaptureOutcome(result.summary(), listOf(CapturedFile("", "jpg", bytes)), size)
    }
}
