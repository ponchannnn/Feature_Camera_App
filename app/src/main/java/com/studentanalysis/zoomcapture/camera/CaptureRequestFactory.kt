package com.studentanalysis.zoomcapture.camera

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.capability.RectI

/** ShotParams から CaptureRequest の設定値を組み立てる */
class CaptureRequestFactory(private val cap: CapabilityReport) {

    /**
     * プレビューと静止画に共通の設定を入れる。
     * maxResolution が true なら、センサーの最大解像度モードの座標系で切り出しと測光領域を指定する。
     */
    fun applyCommon(b: CaptureRequest.Builder, p: ShotParams, maxResolution: Boolean = false) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (cap.zoomRatioSupported && p.zoomRatio != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (maxResolution) 1f else p.zoomRatio)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cap.settingsOverrideZoom) {
            // ズームの変更を、ほかの設定より先に反映させる(切り替えを速くする)
            b.set(CaptureRequest.CONTROL_SETTINGS_OVERRIDE, CameraMetadata.CONTROL_SETTINGS_OVERRIDE_ZOOM)
        }
        if (maxResolution) {
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
        }

        val crop = (if (maxResolution) p.maxResolutionCrop else null) ?: p.cropRegion
        val cropRect = crop.toRect()
        b.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        val metering = arrayOf(MeteringRectangle(cropRect, MeteringRectangle.METERING_WEIGHT_MAX))

        if (p.fixedFocusDistance != null && cap.canFixFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, p.fixedFocusDistance)
        } else if (cap.hasAutoFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (cap.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
        }

        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (cap.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
        b.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            p.aeCompensation.coerceIn(cap.aeCompensationMin, cap.aeCompensationMax),
        )

        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, p.awbLock)
    }

    fun applyStill(b: CaptureRequest.Builder, jpegQuality: Int, jpegOrientation: Int) {
        b.set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(1, 100).toByte())
        b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
    }
}

fun RectI.toRect() = Rect(left, top, right, bottom)
