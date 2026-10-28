package com.studentanalysis.zoomcapture.upload

/**
 * 撮影中の送信を一時停止するかどうかを決める。撮影のタイミングを最優先にするため、
 * 直近の撮影で遅延・スキップ・失敗が増えたとき、または端末が非常に熱いときは送信を止める。
 */
class UploadThrottle(
    private val windowShots: Int = 30,
    private val badRatio: Double = 0.2,
    private val minBad: Int = 3,
    private val pauseMs: Long = 60_000,
) {
    private val history = ArrayDeque<Pair<Long, Long>>()
    private var pausedUntilMs = 0L

    var reason: String? = null
        private set

    /** shots: これまでの撮影回数、bad: そのうち遅延・スキップ・失敗の数。一時停止すべきなら true */
    fun update(nowMs: Long, shots: Long, bad: Long, thermalSevere: Boolean): Boolean {
        if (history.lastOrNull()?.first != shots) history.addLast(shots to bad)
        while (history.size > 1 && shots - history.first().first > windowShots) history.removeFirst()

        val (oldShots, oldBad) = history.first()
        val recentShots = shots - oldShots
        val recentBad = bad - oldBad
        if (recentShots > 0 && recentBad >= minBad && recentBad.toDouble() / recentShots >= badRatio) {
            pausedUntilMs = nowMs + pauseMs
            // 同じ遅延で一時停止を延ばし続けないよう、数え直す
            history.clear()
            history.addLast(shots to bad)
        }
        return when {
            thermalSevere -> {
                reason = "端末が熱いため一時停止中"
                true
            }
            nowMs < pausedUntilMs -> {
                reason = "撮影が遅れているため一時停止中"
                true
            }
            else -> {
                reason = null
                false
            }
        }
    }

    fun reset() {
        history.clear()
        pausedUntilMs = 0
        reason = null
    }
}
