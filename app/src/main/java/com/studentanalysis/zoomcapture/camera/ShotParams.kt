package com.studentanalysis.zoomcapture.camera

import com.studentanalysis.zoomcapture.capability.RectI

/** 1つのプリセットを撮影するときのカメラの設定 */
data class ShotParams(
    /** CONTROL_ZOOM_RATIO。null なら指定しない */
    val zoomRatio: Float?,
    /** SCALER_CROP_REGION(AF / AE の測光領域にも使う) */
    val cropRegion: RectI,
    /** ピント位置を固定するときの値。null なら AF */
    val fixedFocusDistance: Float?,
    val aeCompensation: Int,
    val awbLock: Boolean,
    /** 最大解像度モードでの切り出し。最大解像度モード以外では null */
    val maxResolutionCrop: RectI? = null,
)
