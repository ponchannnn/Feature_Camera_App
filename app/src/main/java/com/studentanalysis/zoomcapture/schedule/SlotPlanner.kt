package com.studentanalysis.zoomcapture.schedule

/** 撮影1回分の予定 */
data class Slot(
    /** 何巡目か(0 始まり) */
    val cycle: Long,
    /** 何番目のプリセットか(0 始まり) */
    val index: Int,
    /** セッション開始からの予定時刻(ミリ秒) */
    val offsetMs: Long,
)

/**
 * ラウンドロビンの撮影予定。プリセット i の k 回目の予定時刻は k·T + i·s。
 * 予定時刻は開始時刻からの絶対時刻なので、撮影が遅れてもずれは積み重ならない。
 */
object SlotPlanner {

    fun offsetMs(cycle: Long, index: Int, periodMs: Long, stepMs: Long): Long =
        cycle * periodMs + index * stepMs

    fun slots(presetCount: Int, periodMs: Long, stepMs: Long): Sequence<Slot> {
        require(presetCount > 0) { "プリセットがありません" }
        return generateSequence(0L) { it + 1 }.flatMap { cycle ->
            (0 until presetCount).asSequence().map { i -> Slot(cycle, i, offsetMs(cycle, i, periodMs, stepMs)) }
        }
    }
}
