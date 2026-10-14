package com.studentanalysis.zoomcapture.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** プレビューの設定を切り替えた後、レンズの切り替えとピント・露出が落ち着くのを待つ */
object ConvergenceWaiter {

    /** 世代番号 gen の設定が反映された最初の結果。タイムアウトなら null */
    suspend fun firstResult(results: StateFlow<TotalCaptureResult?>, gen: Int, timeoutMs: Long): TotalCaptureResult? =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
            results.filterNotNull().first { it.request.tag == gen }
        }

    /**
     * 世代番号 gen の設定で、AE(と、requireAf なら AF、ピント固定ならレンズの移動)が落ち着いた結果。
     * タイムアウトなら null(その場合も撮影は続ける)。
     */
    suspend fun awaitConverged(
        results: StateFlow<TotalCaptureResult?>,
        gen: Int,
        requireAf: Boolean,
        timeoutMs: Long,
    ): TotalCaptureResult? = withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
        results.filterNotNull().first { it.request.tag == gen && isConverged(it, requireAf) }
    }

    fun isConverged(r: CaptureResult, requireAf: Boolean): Boolean {
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED

        val lensOk = r.get(CaptureResult.LENS_STATE) != CaptureResult.LENS_STATE_MOVING

        val af = r.get(CaptureResult.CONTROL_AF_STATE)
        val afOk = !requireAf || af == null || af in AF_DONE

        return aeOk && lensOk && afOk
    }

    private val AF_DONE = setOf(
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
    )
}
