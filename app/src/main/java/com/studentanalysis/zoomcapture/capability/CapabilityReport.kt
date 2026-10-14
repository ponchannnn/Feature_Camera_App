package com.studentanalysis.zoomcapture.capability

import com.studentanalysis.zoomcapture.preset.CaptureMode
import com.studentanalysis.zoomcapture.zoom.ZoomCapability
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class SizeI(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
    val aspect: Double get() = width.toDouble() / height
    override fun toString(): String = "${width}x$height"
}

@Serializable
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Serializable
data class PhysicalLens(
    val id: String,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val pixelArray: SizeI,
    /** 論理カメラのズーム倍率 1.0 の画角を基準にした、このレンズの倍率(超広角なら 1 未満、望遠なら 1 より大きい) */
    val equivalentZoom: Float,
)

/**
 * 端末の背面カメラの機能。CapabilityProbe が Camera2 から調べて作る。
 * Android に依存しない普通のデータなので、テストでは架空の端末を作って使う。
 */
@Serializable
data class CapabilityReport(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val cameraId: String,
    val hardwareLevel: String,
    val isLogicalMultiCamera: Boolean,
    val physicalLenses: List<PhysicalLens>,
    /** CONTROL_ZOOM_RATIO が使えるか */
    val zoomRatioSupported: Boolean,
    val zoomRatioMin: Float,
    val zoomRatioMax: Float,
    val maxDigitalZoom: Float,
    val manualSensor: Boolean,
    val rawSupported: Boolean,
    val ultraHighResolution: Boolean,
    /** CONTROL_SETTINGS_OVERRIDE_ZOOM(API 34)が使えるか */
    val settingsOverrideZoom: Boolean,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    /** 0 なら固定焦点 */
    val minFocusDistance: Float,
    val aeCompensationMin: Int,
    val aeCompensationMax: Int,
    val aeCompensationStep: Float,
    val sensorOrientation: Int,
    /** SENSOR_TIMESTAMP が elapsedRealtimeNanos と同じ時間軸か */
    val timestampRealtime: Boolean,
    val activeArray: RectI,
    val activeArrayMaxResolution: RectI?,
    val jpegSizes: List<SizeI>,
    val rawSizes: List<SizeI>,
    val maxResolutionJpegSizes: List<SizeI>,
    val previewSizes: List<SizeI>,
    /** 検出された Camera Extensions の種類(撮影にはまだ使っていない) */
    val supportedExtensions: List<String>,
) {
    val activeArraySize: SizeI get() = SizeI(activeArray.width, activeArray.height)

    /** 一番広い画角のズーム倍率 */
    val zoomMin: Float get() = if (zoomRatioSupported) zoomRatioMin else 1f

    val zoomMax: Float get() = if (zoomRatioSupported) zoomRatioMax else maxDigitalZoom

    val hasAutoFocus: Boolean get() = minFocusDistance > 0f

    /** ピント位置を手動で固定できるか */
    val canFixFocus: Boolean get() = manualSensor && hasAutoFocus

    fun supports(mode: CaptureMode): Boolean = when (mode) {
        CaptureMode.STANDARD, CaptureMode.BURST -> jpegSizes.isNotEmpty()
        CaptureMode.RAW -> rawSupported && rawSizes.isNotEmpty()
        CaptureMode.MAX_RESOLUTION ->
            ultraHighResolution && activeArrayMaxResolution != null && maxResolutionJpegSizes.isNotEmpty()
    }

    fun supportedModes(): List<CaptureMode> = CaptureMode.entries.filter { supports(it) }

    /** 通常の静止画のサイズ。有効画素領域と同じアスペクト比の中で最大のもの */
    fun stillSize(): SizeI = largestWithAspect(jpegSizes, activeArraySize.aspect)

    fun rawSize(): SizeI? = rawSizes.maxByOrNull { it.pixels }

    fun maxResolutionStillSize(): SizeI? {
        val array = activeArrayMaxResolution ?: return null
        if (maxResolutionJpegSizes.isEmpty()) return null
        return largestWithAspect(maxResolutionJpegSizes, array.width.toDouble() / array.height)
    }

    /** プレビューのサイズ。有効画素領域と同じアスペクト比で、幅 1920 以下の最大のもの */
    fun previewSize(): SizeI {
        val small = previewSizes.filter { it.width <= 1920 }.ifEmpty { previewSizes }
        return largestWithAspect(small, activeArraySize.aspect)
    }

    fun zoomCapability(): ZoomCapability = ZoomCapability(
        zoomRatioSupported = zoomRatioSupported,
        zoomMin = zoomMin,
        zoomMax = zoomMax,
        activeArraySize = activeArraySize,
        maxResolutionArraySize = activeArrayMaxResolution?.let { SizeI(it.width, it.height) },
        lenses = physicalLenses,
    )

    private fun largestWithAspect(sizes: List<SizeI>, aspect: Double): SizeI {
        require(sizes.isNotEmpty()) { "出力サイズがありません" }
        val matching = sizes.filter { abs(it.aspect - aspect) / aspect < 0.01 }
        return (matching.ifEmpty { sizes }).maxBy { it.pixels }
    }
}
