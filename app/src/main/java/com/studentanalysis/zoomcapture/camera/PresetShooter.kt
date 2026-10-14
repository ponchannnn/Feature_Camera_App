package com.studentanalysis.zoomcapture.camera

import android.os.SystemClock
import com.studentanalysis.zoomcapture.camera.strategy.CaptureContext
import com.studentanalysis.zoomcapture.camera.strategy.CaptureOutcome
import com.studentanalysis.zoomcapture.camera.strategy.CaptureStrategies
import com.studentanalysis.zoomcapture.capability.RectI
import com.studentanalysis.zoomcapture.preset.FocusMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.zoom.ZoomPlan

/**
 * プリセット1つ分の「切り替え → 収束待ち → 撮影」。自動撮影とテスト撮影の両方から使う。
 */
class PresetShooter(private val controller: CameraController, private val jpegOrientation: Int) {

    private val cap = controller.cap

    fun paramsFor(preset: Preset, plan: ZoomPlan, awbLock: Boolean): ShotParams = ShotParams(
        zoomRatio = plan.zoomRatio,
        cropRegion = plan.cropRegion,
        fixedFocusDistance = if (preset.focusMode == FocusMode.FIXED && cap.canFixFocus) preset.fixedFocusDistance else null,
        aeCompensation = preset.exposureCompensation,
        awbLock = awbLock,
        maxResolutionCrop = plan.maxResolutionCrop,
    )

    /** 一番広い画角を表示するための設定(プリセット編集画面のプレビュー用) */
    fun wideParams(): ShotParams = ShotParams(
        zoomRatio = if (cap.zoomRatioSupported) cap.zoomMin else null,
        cropRegion = RectI(0, 0, cap.activeArray.width, cap.activeArray.height),
        fixedFocusDistance = null,
        aeCompensation = 0,
        awbLock = false,
    )

    /**
     * プレビューをプリセットの設定に切り替え、deadlineElapsedMs(SystemClock.elapsedRealtime)まで収束を待つ。
     * ピント固定でも、登録時と違うレンズが使われた場合は AF に切り替える(ピント位置の値の意味が変わるため)。
     */
    suspend fun prepare(preset: Preset, plan: ZoomPlan, awbLock: Boolean, deadlineElapsedMs: Long): ShotParams {
        var params = paramsFor(preset, plan, awbLock)
        var gen = controller.setPreview(params)

        if (params.fixedFocusDistance != null && preset.fixedFocusPhysicalId != null) {
            val first = ConvergenceWaiter.firstResult(controller.latestResult, gen, remaining(deadlineElapsedMs))
            val active = first?.summary()?.physicalCameraId
            if (active != null && active != preset.fixedFocusPhysicalId) {
                params = params.copy(fixedFocusDistance = null)
                gen = controller.setPreview(params)
            }
        }

        ConvergenceWaiter.awaitConverged(
            controller.latestResult,
            gen,
            requireAf = params.fixedFocusDistance == null && cap.hasAutoFocus,
            timeoutMs = remaining(deadlineElapsedMs),
        )
        return params
    }

    suspend fun shoot(preset: Preset, params: ShotParams, jpegQuality: Int): CaptureOutcome =
        CaptureStrategies.forMode(preset.captureMode)
            .capture(CaptureContext(controller, jpegQuality, jpegOrientation), preset, params)

    private fun remaining(deadline: Long) = deadline - SystemClock.elapsedRealtime()
}
