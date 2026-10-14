package com.studentanalysis.zoomcapture.zoom

import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.capability.SizeI
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 正規化座標(0〜1)の矩形 */
@Serializable
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 左右・上下の向きをそろえ、0〜1 に収める */
    fun normalized(): NormRect = NormRect(
        min(left, right).coerceIn(0f, 1f),
        min(top, bottom).coerceIn(0f, 1f),
        max(left, right).coerceIn(0f, 1f),
        max(top, bottom).coerceIn(0f, 1f),
    )

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * 画面に表示しているプレビューの座標と、センサーの座標の変換。
 *
 * - 「画面の正規化座標」: 正立して表示されているプレビューの左上を (0,0)、右下を (1,1) とする
 * - 「センサーの正規化座標」: センサーの画像(一番広い画角)の左上を (0,0)、右下を (1,1) とする
 * - rotation: センサーの画像を時計回りに何度回すと画面上で正立するか(0 / 90 / 180 / 270)
 */
object CoordinateMapper {

    /** センサーの取り付け向きと画面の回転から、rotation を求める(背面カメラ用) */
    fun relativeRotation(sensorOrientation: Int, displayRotationDegrees: Int): Int =
        ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360

    fun displayToSensor(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        0 -> u to v
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> throw IllegalArgumentException("rotation=$rotation")
    }

    fun sensorToDisplay(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        0 -> x to y
        90 -> 1f - y to x
        180 -> 1f - x to 1f - y
        270 -> y to 1f - x
        else -> throw IllegalArgumentException("rotation=$rotation")
    }

    fun displayRectToSensor(rect: NormRect, rotation: Int): NormRect =
        mapRect(rect) { u, v -> displayToSensor(u, v, rotation) }

    fun sensorRectToDisplay(rect: NormRect, rotation: Int): NormRect =
        mapRect(rect) { x, y -> sensorToDisplay(x, y, rotation) }

    /** センサーのアスペクト比(幅/高さ)から、画面に表示するときのアスペクト比を求める */
    fun displayAspect(sensorAspect: Float, rotation: Int): Float =
        if (rotation % 180 == 0) sensorAspect else 1f / sensorAspect

    /** 正規化座標の矩形を、指定サイズの画素座標に変換する */
    fun normToPixels(rect: NormRect, size: SizeI): RectI {
        val l = (rect.left * size.width).roundToInt().coerceIn(0, size.width)
        val t = (rect.top * size.height).roundToInt().coerceIn(0, size.height)
        val r = (rect.right * size.width).roundToInt().coerceIn(l, size.width)
        val b = (rect.bottom * size.height).roundToInt().coerceIn(t, size.height)
        return RectI(l, t, r, b)
    }

    private inline fun mapRect(rect: NormRect, f: (Float, Float) -> Pair<Float, Float>): NormRect {
        val (x1, y1) = f(rect.left, rect.top)
        val (x2, y2) = f(rect.right, rect.bottom)
        return NormRect(x1, y1, x2, y2).normalized()
    }
}
