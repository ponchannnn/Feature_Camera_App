package com.studentanalysis.zoomcapture.zoom

import com.studentanalysis.zoomcapture.capability.PhysicalLens
import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.capability.SizeI
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** ZoomPlanner が必要とする端末の情報 */
data class ZoomCapability(
    val zoomRatioSupported: Boolean,
    /** 一番広い画角のズーム倍率(超広角があれば 1 未満) */
    val zoomMin: Float,
    val zoomMax: Float,
    val activeArraySize: SizeI,
    val maxResolutionArraySize: SizeI?,
    val lenses: List<PhysicalLens>,
)

/**
 * プリセット1つを撮影するためのズームの計画。
 * 矩形の座標は、一番広い画角(ズーム倍率 zoomMin)を基準にした正規化座標。
 */
@Serializable
data class ZoomPlan(
    val requestedRect: NormRect,
    /** 出力のアスペクト比に広げ、画角の内側に収めた、実際に写る範囲 */
    val fittedRect: NormRect,
    /** CONTROL_ZOOM_RATIO に指定する値。null なら CONTROL_ZOOM_RATIO を使わない */
    val zoomRatio: Float?,
    /** SCALER_CROP_REGION(zoomRatio を適用した後の座標系。有効画素領域の左上が原点) */
    val cropRegion: RectI,
    /** ズーム倍率 1.0 の画角に対する、実効的なズーム倍率 */
    val totalZoom: Float,
    val expectedLensId: String?,
    /** 使われる見込みのレンズの倍率 */
    val opticalZoom: Float,
    /** ズーム倍率の上限のため、指定した矩形より広く写る */
    val clampedByMaxZoom: Boolean,
    /** 最大解像度モードでの切り出し(最大解像度の有効画素領域の座標系)。最大解像度モード以外では null */
    val maxResolutionCrop: RectI? = null,
) {
    val digitalZoom: Float get() = totalZoom / opticalZoom
}

/**
 * 「撮りたい矩形」から、それがちょうど収まるズーム倍率と切り出し位置を計算する。
 *
 * 1. 矩形を、中心を保ったまま出力のアスペクト比(有効画素領域と同じ)に広げる
 * 2. ズーム倍率の上限で決まる最小サイズより小さければ広げ、画角からはみ出せば内側へずらす
 * 3. 画角の中央を中心にズームしても矩形が収まる最大の倍率を CONTROL_ZOOM_RATIO に指定する
 *    (望遠レンズのある端末では、これで自動的に望遠レンズに切り替わる)
 * 4. 残りのズームと位置のずらしを SCALER_CROP_REGION で指定する
 */
object ZoomPlanner {

    fun plan(requested: NormRect, cap: ZoomCapability): ZoomPlan {
        val minSide = min(1f, cap.zoomMin / cap.zoomMax)
        val (fitted, clamped) = fitSquare(requested, minSide)
        val side = fitted.width
        val totalZoom = cap.zoomMin / side

        // 画角の中央からのズームで矩形が収まる最大の倍率
        val zoomBase = if (!cap.zoomRatioSupported) {
            cap.zoomMin
        } else {
            val d = maxOf(
                abs(fitted.left - 0.5f), abs(fitted.right - 0.5f),
                abs(fitted.top - 0.5f), abs(fitted.bottom - 0.5f),
            )
            val z = if (d <= 0f) totalZoom else cap.zoomMin / (2f * d)
            z.coerceIn(cap.zoomMin, max(cap.zoomMin, totalZoom))
        }

        // zoomBase で見えている範囲(一番広い画角に対する割合)の中での位置
        val visible = cap.zoomMin / zoomBase
        val crop = CoordinateMapper.normToPixels(toInner(fitted, visible), cap.activeArraySize)

        val lens = LensModel.expectedLens(cap.lenses, zoomBase)
        return ZoomPlan(
            requestedRect = requested,
            fittedRect = fitted,
            zoomRatio = if (cap.zoomRatioSupported) zoomBase else null,
            cropRegion = crop,
            totalZoom = totalZoom,
            expectedLensId = lens?.id,
            opticalZoom = min(lens?.equivalentZoom ?: 1f, totalZoom),
            clampedByMaxZoom = clamped,
        )
    }

    /**
     * センサーの最大解像度モード用の計画。最大解像度モードはズーム倍率 1.0 のレンズでしか使えないので、
     * 倍率 1.0 の画角の中で切り出す。プレビュー(ピント・露出合わせ)も倍率 1.0 で同じ範囲を切り出す。
     */
    fun planMaxResolution(requested: NormRect, cap: ZoomCapability): ZoomPlan {
        val maxResSize = requireNotNull(cap.maxResolutionArraySize) { "最大解像度モードに対応していません" }
        // 一番広い画角の中で、倍率 1.0 の画角が占める割合
        val oneX = min(1f, cap.zoomMin)
        val inOneX = toInner(requested.normalized(), oneX)
        val (fittedInOneX, clamped) = fitSquare(inOneX, min(1f, 1f / cap.zoomMax))

        val origin = 0.5f - oneX / 2f
        val fitted = NormRect(
            origin + fittedInOneX.left * oneX,
            origin + fittedInOneX.top * oneX,
            origin + fittedInOneX.right * oneX,
            origin + fittedInOneX.bottom * oneX,
        )
        val lens = LensModel.expectedLens(cap.lenses, 1f)
        val totalZoom = 1f / fittedInOneX.width
        return ZoomPlan(
            requestedRect = requested,
            fittedRect = fitted,
            zoomRatio = if (cap.zoomRatioSupported) 1f else null,
            cropRegion = CoordinateMapper.normToPixels(fittedInOneX, cap.activeArraySize),
            totalZoom = totalZoom,
            expectedLensId = lens?.id,
            opticalZoom = min(lens?.equivalentZoom ?: 1f, totalZoom),
            clampedByMaxZoom = clamped,
            maxResolutionCrop = CoordinateMapper.normToPixels(fittedInOneX, maxResSize),
        )
    }

    /**
     * 矩形を、中心を保ったまま正規化座標で正方形(= 有効画素領域と同じアスペクト比)に広げ、
     * 最小サイズを確保して 0〜1 の内側に収める。
     */
    internal fun fitSquare(requested: NormRect, minSide: Float): Pair<NormRect, Boolean> {
        val r = requested.normalized()
        val raw = max(r.width, r.height)
        val clamped = raw < minSide - 1e-4f
        val side = raw.coerceIn(minSide, 1f)
        val cx = r.centerX.coerceIn(side / 2f, 1f - side / 2f)
        val cy = r.centerY.coerceIn(side / 2f, 1f - side / 2f)
        return NormRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f) to clamped
    }

    /** 中央にある、割合 visible の範囲の中での正規化座標に変換する */
    private fun toInner(rect: NormRect, visible: Float): NormRect {
        val origin = 0.5f - visible / 2f
        return NormRect(
            (rect.left - origin) / visible,
            (rect.top - origin) / visible,
            (rect.right - origin) / visible,
            (rect.bottom - origin) / visible,
        ).normalized()
    }
}
