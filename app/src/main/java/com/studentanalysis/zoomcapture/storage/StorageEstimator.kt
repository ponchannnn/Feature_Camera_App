package com.studentanalysis.zoomcapture.storage

import com.studentanalysis.zoomcapture.preset.CaptureMode

/** 1つのプリセットの撮影1回で保存するもの */
data class ShotSpec(val mode: CaptureMode, val burstCount: Int = 1)

/** 出力するサイズ(画素数) */
data class OutputPixels(val still: Long, val maxResolution: Long?, val raw: Long?)

/** 必要な容量と、撮り続けられる時間の見積もり */
object StorageEstimator {

    /** JPEG の1画素あたりのバイト数の目安(画質 95 で 12MP ≈ 3.4MB) */
    fun jpegBytesPerPixel(quality: Int): Double =
        0.08 + (quality.coerceIn(50, 100) - 50) / 50.0 * 0.22

    const val RAW_BYTES_PER_PIXEL = 2.0

    fun bytesPerShot(spec: ShotSpec, pixels: OutputPixels, jpegQuality: Int): Long {
        val jpeg = jpegBytesPerPixel(jpegQuality)
        val bytes = when (spec.mode) {
            CaptureMode.STANDARD -> pixels.still * jpeg
            CaptureMode.MAX_RESOLUTION -> (pixels.maxResolution ?: pixels.still) * jpeg
            CaptureMode.RAW -> pixels.still * jpeg + (pixels.raw ?: pixels.still) * RAW_BYTES_PER_PIXEL
            CaptureMode.BURST -> pixels.still * jpeg * spec.burstCount.coerceAtLeast(1)
        }
        return bytes.toLong()
    }

    /** 1秒あたりに書き込むバイト数。各プリセットは periodSec ごとに1回撮影される */
    fun bytesPerSecond(specs: List<ShotSpec>, pixels: OutputPixels, jpegQuality: Int, periodSec: Double): Double {
        if (periodSec <= 0) return 0.0
        return specs.sumOf { bytesPerShot(it, pixels, jpegQuality) } / periodSec
    }

    fun bytesFor(durationSec: Double, bytesPerSecond: Double): Long = (durationSec * bytesPerSecond).toLong()

    /** 空き容量 freeBytes のうち reserveBytes を残して、あと何秒撮り続けられるか */
    fun secondsRemaining(freeBytes: Long, reserveBytes: Long, bytesPerSecond: Double): Double {
        if (bytesPerSecond <= 0) return Double.POSITIVE_INFINITY
        return ((freeBytes - reserveBytes).coerceAtLeast(0)) / bytesPerSecond
    }
}
