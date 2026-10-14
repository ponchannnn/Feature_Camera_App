package com.studentanalysis.zoomcapture.zoom

import com.studentanalysis.zoomcapture.capability.PhysicalLens
import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.capability.SizeI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPlannerTest {

    private val array = SizeI(4000, 3000)

    private fun lens(id: String, zoom: Float) = PhysicalLens(id, 4f * zoom, 6f, SizeI(4000, 3000), zoom)

    /** Pixel 6a 相当: 望遠なし、デジタルズーム 7 倍まで */
    private val singleLens = ZoomCapability(true, 1f, 7f, array, null, listOf(lens("2", 1f)))

    /** 超広角 0.5x・標準 1x・望遠 3x の端末 */
    private val tripleLens = ZoomCapability(
        true, 0.5f, 30f, array, null, listOf(lens("u", 0.5f), lens("w", 1f), lens("t", 3f)),
    )

    /** CONTROL_ZOOM_RATIO が使えない端末 */
    private val legacy = ZoomCapability(false, 1f, 4f, array, null, emptyList())

    private fun assertRect(expected: NormRect, actual: NormRect, eps: Float = 1e-4f) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test
    fun fullFrameIsOneX() {
        val plan = ZoomPlanner.plan(NormRect.FULL, singleLens)
        assertEquals(1f, plan.totalZoom, 1e-4f)
        assertEquals(1f, plan.zoomRatio!!, 1e-4f)
        assertEquals(RectI(0, 0, 4000, 3000), plan.cropRegion)
        assertFalse(plan.clampedByMaxZoom)
    }

    @Test
    fun rectIsExpandedToSquareAroundItsCenter() {
        // 横長の矩形(幅 0.4、高さ 0.2)は、中心を保って 0.4 x 0.4 に広げる
        val plan = ZoomPlanner.plan(NormRect(0.3f, 0.4f, 0.7f, 0.6f), singleLens)
        assertRect(NormRect(0.3f, 0.3f, 0.7f, 0.7f), plan.fittedRect)
        assertEquals(2.5f, plan.totalZoom, 1e-4f)
    }

    @Test
    fun centeredRectUsesZoomRatioOnly() {
        val plan = ZoomPlanner.plan(NormRect(0.25f, 0.25f, 0.75f, 0.75f), singleLens)
        assertEquals(2f, plan.zoomRatio!!, 1e-4f)
        assertEquals(RectI(0, 0, 4000, 3000), plan.cropRegion)
    }

    @Test
    fun offCenterRectUsesZoomRatioThenCrop() {
        // 左上の 1/4: 中央からのズームでは 1x でしか収まらないので、残りは切り出しで指定する
        val plan = ZoomPlanner.plan(NormRect(0f, 0f, 0.5f, 0.5f), singleLens)
        assertEquals(2f, plan.totalZoom, 1e-4f)
        assertEquals(1f, plan.zoomRatio!!, 1e-4f)
        assertEquals(RectI(0, 0, 2000, 1500), plan.cropRegion)
    }

    @Test
    fun partiallyOffCenterRectUsesIntermediateZoomRatio() {
        // 中心から最大 0.375 離れている → zoomRatio = 1 / (2 × 0.375) = 1.333…
        val plan = ZoomPlanner.plan(NormRect(0.5f, 0.5f, 0.875f, 0.875f), singleLens)
        assertEquals(1f / 0.75f, plan.zoomRatio!!, 1e-3f)
        // zoomRatio 適用後に見えている範囲 [0.125, 0.875] の中での位置
        val v = 0.75f
        val expected = NormRect((0.5f - 0.125f) / v, (0.5f - 0.125f) / v, 1f, 1f)
        assertEquals(CoordinateMapper.normToPixels(expected, array), plan.cropRegion)
    }

    @Test
    fun tinyRectIsClampedByMaxZoom() {
        val plan = ZoomPlanner.plan(NormRect(0.5f, 0.5f, 0.51f, 0.51f), singleLens)
        assertTrue(plan.clampedByMaxZoom)
        assertEquals(7f, plan.totalZoom, 1e-3f)
    }

    @Test
    fun squareThatCrossesEdgeIsShiftedInside() {
        // 右端の縦長の矩形を正方形に広げると右にはみ出すので、内側へずらす
        val plan = ZoomPlanner.plan(NormRect(0.9f, 0.4f, 1f, 0.8f), singleLens)
        assertRect(NormRect(0.6f, 0.4f, 1f, 0.8f), plan.fittedRect)
    }

    @Test
    fun partOutsideFrameIsIgnored() {
        // 画角の外(ドラッグがプレビューの外まで出た場合)は切り捨てる
        val plan = ZoomPlanner.plan(NormRect(0.8f, 0.8f, 1.2f, 1.2f), singleLens)
        assertRect(NormRect(0.8f, 0.8f, 1f, 1f), plan.fittedRect)
    }

    @Test
    fun centeredRectOnTripleLensSwitchesToTelephoto() {
        // 一番広い画角は 0.5x。中央の 1/8 の範囲は 4x → 中央からのズームで収まるので zoomRatio = 4、望遠 (3x) を使う見込み
        val plan = ZoomPlanner.plan(NormRect(0.4375f, 0.4375f, 0.5625f, 0.5625f), tripleLens)
        assertEquals(4f, plan.totalZoom, 1e-3f)
        assertEquals(4f, plan.zoomRatio!!, 1e-3f)
        assertEquals("t", plan.expectedLensId)
        assertEquals(3f, plan.opticalZoom, 1e-4f)
        assertEquals(4f / 3f, plan.digitalZoom, 1e-3f)
    }

    @Test
    fun cornerRectOnTripleLensCannotUseTelephoto() {
        // 一番広い画角の左上の隅は、望遠レンズの画角に入らないので超広角のまま切り出す
        val plan = ZoomPlanner.plan(NormRect(0f, 0f, 0.125f, 0.125f), tripleLens)
        assertEquals(0.5f, plan.zoomRatio!!, 1e-4f)
        assertEquals("u", plan.expectedLensId)
        assertEquals(RectI(0, 0, 500, 375), plan.cropRegion)
    }

    @Test
    fun legacyDeviceUsesCropRegionOnly() {
        val plan = ZoomPlanner.plan(NormRect(0.5f, 0f, 1f, 0.5f), legacy)
        assertNull(plan.zoomRatio)
        assertEquals(RectI(2000, 0, 4000, 1500), plan.cropRegion)
        assertNull(plan.expectedLensId)
        assertEquals(1f, plan.opticalZoom, 1e-4f)
    }

    @Test
    fun maxResolutionPlanCropsInMaxResolutionArray() {
        val cap = singleLens.copy(maxResolutionArraySize = SizeI(8000, 6000))
        val plan = ZoomPlanner.planMaxResolution(NormRect(0f, 0f, 0.5f, 0.5f), cap)
        assertEquals(1f, plan.zoomRatio!!, 1e-4f)
        assertEquals(RectI(0, 0, 2000, 1500), plan.cropRegion)
        assertEquals(RectI(0, 0, 4000, 3000), plan.maxResolutionCrop)
    }

    @Test
    fun maxResolutionPlanOnUltraWideDeviceConvertsToOneXFrame() {
        // 一番広い画角が 0.5x のとき、1x の画角は中央の [0.25, 0.75]
        val cap = tripleLens.copy(maxResolutionArraySize = SizeI(8000, 6000))
        val plan = ZoomPlanner.planMaxResolution(NormRect(0.25f, 0.25f, 0.5f, 0.5f), cap)
        assertRect(NormRect(0.25f, 0.25f, 0.5f, 0.5f), plan.fittedRect)
        assertEquals(RectI(0, 0, 2000, 1500), plan.cropRegion)
        assertEquals(RectI(0, 0, 4000, 3000), plan.maxResolutionCrop)
        assertEquals(2f, plan.totalZoom, 1e-4f)
    }
}
