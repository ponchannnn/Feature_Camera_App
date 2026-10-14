package com.studentanalysis.zoomcapture.zoom

import com.studentanalysis.zoomcapture.capability.PhysicalLens

/** 物理レンズのズーム倍率に関する計算 */
object LensModel {

    /**
     * 基準のレンズ(論理カメラのズーム倍率 1.0)に対する、あるレンズの倍率。
     * 画角は「センサー幅 ÷ 焦点距離」に比例するので、その逆数の比を取る。
     */
    fun equivalentZoom(
        focalLengthMm: Float,
        sensorWidthMm: Float,
        referenceFocalLengthMm: Float,
        referenceSensorWidthMm: Float,
    ): Float = (focalLengthMm / sensorWidthMm) / (referenceFocalLengthMm / referenceSensorWidthMm)

    /**
     * CONTROL_ZOOM_RATIO に zoomRatio を指定したときに、端末が使う見込みのレンズ。
     * 倍率が zoomRatio 以下のレンズのうち、一番倍率が高いものを選ぶ。
     * 実際の切り替えは端末が決める(暗いときに望遠を使わない機種もある)ので、あくまで見込み。
     */
    fun expectedLens(lenses: List<PhysicalLens>, zoomRatio: Float): PhysicalLens? {
        if (lenses.isEmpty()) return null
        return lenses.filter { it.equivalentZoom <= zoomRatio * 1.001f }.maxByOrNull { it.equivalentZoom }
            ?: lenses.minBy { it.equivalentZoom }
    }
}
