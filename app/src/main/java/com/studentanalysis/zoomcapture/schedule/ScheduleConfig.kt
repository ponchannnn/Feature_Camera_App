package com.studentanalysis.zoomcapture.schedule

import kotlin.math.roundToLong

data class ScheduleConfig(val periodSec: Double, val stepSec: Double) {
    val periodMs: Long get() = (periodSec * 1000).roundToLong()
    val stepMs: Long get() = (stepSec * 1000).roundToLong()
}

data class ScheduleCheck(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/** 撮影設定の制約(T ≥ N×s、s ≥ 計測した下限)のチェック */
object ScheduleValidator {

    /** 計測した所要時間に加える余裕 */
    const val LATENCY_MARGIN_MS = 300L

    /** 計測していなくても下回れない、プリセット間隔の下限 */
    const val ABSOLUTE_MIN_STEP_MS = 300L

    /** 計測した所要時間から求めた、プリセット間隔の下限。1つも計測していなければ null */
    fun measuredMinStepMs(latenciesMs: List<Long?>): Long? =
        latenciesMs.filterNotNull().maxOrNull()?.let { it + LATENCY_MARGIN_MS }

    fun check(config: ScheduleConfig, latenciesMs: List<Long?>): ScheduleCheck {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val n = latenciesMs.size

        if (n == 0) errors += "有効なプリセットがありません"
        if (config.periodMs <= 0) errors += "撮影周期 T は 0 より大きくしてください"
        if (config.stepMs < ABSOLUTE_MIN_STEP_MS) {
            errors += "プリセット間隔 s は %.1f 秒以上にしてください".format(ABSOLUTE_MIN_STEP_MS / 1000.0)
        }
        if (n > 0 && config.periodMs < n * config.stepMs) {
            errors += "撮影周期 T (%.1f 秒) が、プリセット数 N (%d) × 間隔 s (%.1f 秒) = %.1f 秒より短いです"
                .format(config.periodSec, n, config.stepSec, n * config.stepSec)
        }
        measuredMinStepMs(latenciesMs)?.let { min ->
            if (config.stepMs < min) {
                errors += "プリセット間隔 s が、テスト撮影で計測した下限 (%.1f 秒) より短いです".format(min / 1000.0)
            }
        }
        val unmeasured = latenciesMs.count { it == null }
        if (unmeasured > 0) {
            warnings += "テスト撮影をしていないプリセットが $unmeasured 個あります。所要時間を計測しないと、撮影が間に合わない可能性があります"
        }
        return ScheduleCheck(errors, warnings)
    }
}
