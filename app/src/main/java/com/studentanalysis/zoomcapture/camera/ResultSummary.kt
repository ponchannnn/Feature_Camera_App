package com.studentanalysis.zoomcapture.camera

import android.hardware.camera2.CaptureResult
import com.studentanalysis.zoomcapture.capability.RectI

/** CaptureResult から、ログに残す値だけを取り出したもの */
data class ResultSummary(
    val sensorTimestampNs: Long?,
    val cropRegion: RectI?,
    val zoomRatio: Float?,
    val physicalCameraId: String?,
    val focusDistance: Float?,
    val exposureTimeNs: Long?,
    val iso: Int?,
    val afState: String?,
    val aeState: String?,
)

fun CaptureResult.summary(): ResultSummary = ResultSummary(
    sensorTimestampNs = get(CaptureResult.SENSOR_TIMESTAMP),
    cropRegion = get(CaptureResult.SCALER_CROP_REGION)?.let { RectI(it.left, it.top, it.right, it.bottom) },
    zoomRatio = get(CaptureResult.CONTROL_ZOOM_RATIO),
    physicalCameraId = get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
    focusDistance = get(CaptureResult.LENS_FOCUS_DISTANCE),
    exposureTimeNs = get(CaptureResult.SENSOR_EXPOSURE_TIME),
    iso = get(CaptureResult.SENSOR_SENSITIVITY),
    afState = get(CaptureResult.CONTROL_AF_STATE)?.let(::afStateName),
    aeState = get(CaptureResult.CONTROL_AE_STATE)?.let(::aeStateName),
)

private fun afStateName(state: Int): String = when (state) {
    CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
    CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
    CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
    else -> "UNKNOWN_$state"
}

private fun aeStateName(state: Int): String = when (state) {
    CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
    CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
    CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
    CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
    CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
    else -> "UNKNOWN_$state"
}
