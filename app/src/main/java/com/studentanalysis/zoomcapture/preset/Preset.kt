package com.studentanalysis.zoomcapture.preset

import com.studentanalysis.zoomcapture.zoom.NormRect
import kotlinx.serialization.Serializable

@Serializable
enum class CaptureMode(val label: String, val logName: String) {
    STANDARD("標準 JPEG", "standard"),
    MAX_RESOLUTION("最大解像度", "max_resolution"),
    RAW("JPEG+RAW", "raw"),
    BURST("連写", "burst"),
}

@Serializable
enum class FocusMode(val label: String) {
    /** プリセット登録時のピント位置に固定する */
    FIXED("ピント固定"),
    /** 撮影のたびに AF で合わせる */
    AUTO_EACH("毎回 AF"),
}

/** 1つの撮影領域(ズーム+位置) */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    /** ユーザーが指定した矩形。一番広い画角を基準にした、センサーの正規化座標 */
    val targetRect: NormRect,
    val captureMode: CaptureMode = CaptureMode.STANDARD,
    val focusMode: FocusMode = FocusMode.FIXED,
    /** テスト撮影で AF が合わせたピント位置(diopter) */
    val fixedFocusDistance: Float? = null,
    /** fixedFocusDistance を取得したときのレンズ(物理カメラ ID) */
    val fixedFocusPhysicalId: String? = null,
    val exposureCompensation: Int = 0,
    val burstCount: Int = 3,
    /** テスト撮影で計測した、切り替えから撮影完了までの時間 */
    val measuredLatencyMs: Long? = null,
    val enabled: Boolean = true,
) {
    /** 撮影範囲が変わったら、ピント位置と計測した所要時間は使えなくなる */
    fun withTargetRect(rect: NormRect): Preset =
        copy(targetRect = rect, fixedFocusDistance = null, fixedFocusPhysicalId = null, measuredLatencyMs = null)
}

/** 講義室ごとのプリセットの集まりと撮影設定 */
@Serializable
data class PresetSet(
    val name: String,
    /** 作成した端末(別の端末で使うと位置がずれる可能性がある) */
    val deviceModel: String,
    val periodSec: Double = DEFAULT_PERIOD_SEC,
    val stepSec: Double = DEFAULT_STEP_SEC,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    val presets: List<Preset>,
) {
    val enabledPresets: List<Preset> get() = presets.filter { it.enabled }

    fun nextPresetId(): String {
        val max = presets.mapNotNull { it.id.removePrefix("P").toIntOrNull() }.maxOrNull() ?: 0
        return "P%02d".format(max + 1)
    }

    fun replace(preset: Preset): PresetSet = copy(presets = presets.map { if (it.id == preset.id) preset else it })

    companion object {
        const val DEFAULT_PERIOD_SEC = 3.0
        const val DEFAULT_STEP_SEC = 1.0
        const val DEFAULT_JPEG_QUALITY = 95
    }
}
