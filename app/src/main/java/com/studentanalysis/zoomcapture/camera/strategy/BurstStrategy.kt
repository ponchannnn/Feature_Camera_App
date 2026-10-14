package com.studentanalysis.zoomcapture.camera.strategy

import com.studentanalysis.zoomcapture.camera.CameraController
import com.studentanalysis.zoomcapture.camera.OutputKind
import com.studentanalysis.zoomcapture.camera.ShotParams
import com.studentanalysis.zoomcapture.camera.summary
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.storage.CapturedFile

/**
 * 連写: 同じ設定で JPEG を複数枚撮る。合成(ノイズ除去・超解像)は PC 側で行う。
 * ファイル名の末尾に _b01, _b02 ... を付ける。
 */
object BurstStrategy : CaptureStrategy {
    override val mode = CaptureMode.BURST

    override suspend fun capture(ctx: CaptureContext, preset: Preset, params: ShotParams): CaptureOutcome {
        val c = ctx.controller
        val count = preset.burstCount.coerceIn(2, CameraController.MAX_BURST)
        c.drainImages(OutputKind.JPEG)
        val request = c.buildStill(params, listOf(OutputKind.JPEG), ctx.jpegQuality, ctx.jpegOrientation)
        val results = c.capture(List(count) { request })
        val images = c.awaitImages(OutputKind.JPEG, count, timeoutMs = 5_000L + count * 1_000L)
        var size = SizeI(0, 0)
        val files = images.sortedBy { it.timestamp }.mapIndexed { i, image ->
            image.use {
                size = SizeI(it.width, it.height)
                CapturedFile("_b%02d".format(i + 1), "jpg", it.jpegBytes())
            }
        }
        return CaptureOutcome(results.first().summary(), files, size)
    }
}
