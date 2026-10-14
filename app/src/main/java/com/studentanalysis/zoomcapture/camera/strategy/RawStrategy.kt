package com.studentanalysis.zoomcapture.camera.strategy

import android.hardware.camera2.DngCreator
import androidx.exifinterface.media.ExifInterface
import com.studentanalysis.zoomcapture.camera.OutputKind
import com.studentanalysis.zoomcapture.camera.ShotParams
import com.studentanalysis.zoomcapture.camera.summary
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.storage.CapturedFile
import java.io.ByteArrayOutputStream

/**
 * JPEG と RAW(DNG)を同時に撮る。RAW はセンサー全体を記録するので切り出しは効かないが、
 * PC 側で JPEG と同じ範囲を切り出して現像できるよう、切り出し矩形はログに残る。
 */
object RawStrategy : CaptureStrategy {
    override val mode = CaptureMode.RAW

    override suspend fun capture(ctx: CaptureContext, preset: Preset, params: ShotParams): CaptureOutcome {
        val c = ctx.controller
        c.drainImages(OutputKind.JPEG)
        c.drainImages(OutputKind.RAW)
        val request = c.buildStill(params, listOf(OutputKind.JPEG, OutputKind.RAW), ctx.jpegQuality, ctx.jpegOrientation)
        val result = c.capture(listOf(request)).single()
        val jpeg = c.awaitImages(OutputKind.JPEG, 1).single()
        val (jpegBytes, size) = jpeg.use { it.jpegBytes() to SizeI(it.width, it.height) }
        val raw = c.awaitImages(OutputKind.RAW, 1).single()
        val dngBytes = raw.use { image ->
            ByteArrayOutputStream().use { out ->
                DngCreator(c.characteristics, result).use { dng ->
                    dng.setOrientation(exifOrientation(ctx.jpegOrientation))
                    dng.writeImage(out, image)
                }
                out.toByteArray()
            }
        }
        return CaptureOutcome(
            result.summary(),
            listOf(CapturedFile("", "jpg", jpegBytes), CapturedFile("", "dng", dngBytes)),
            size,
        )
    }

    private fun exifOrientation(rotation: Int): Int = when (rotation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
