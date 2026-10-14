package com.studentanalysis.zoomcapture.preset

import com.studentanalysis.zoomcapture.zoom.NormRect

/**
 * デフォルトのプリセットセット: 「全体」(1x)、「奥・左」「奥・右」(1x の画角の上半分の左右)。
 * 教室の奥の席は画面の上の方に写る想定の仮の値なので、実際の教室で調整する。
 */
object DefaultPresets {

    fun create(name: String, deviceModel: String, zoomMin: Float): PresetSet {
        // 一番広い画角の中で、倍率 1.0 の画角が占める範囲
        val oneX = zoomMin.coerceAtMost(1f)
        val o = 0.5f - oneX / 2f
        val half = oneX / 2f
        return PresetSet(
            name = name,
            deviceModel = deviceModel,
            presets = listOf(
                Preset("P01", "全体", NormRect(o, o, o + oneX, o + oneX)),
                Preset("P02", "奥・左", NormRect(o, o, o + half, o + half)),
                Preset("P03", "奥・右", NormRect(o + half, o, o + oneX, o + half)),
            ),
        )
    }
}
