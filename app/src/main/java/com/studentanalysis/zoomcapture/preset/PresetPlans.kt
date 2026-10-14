package com.studentanalysis.zoomcapture.preset

import com.studentanalysis.zoomcapture.zoom.ZoomCapability
import com.studentanalysis.zoomcapture.zoom.ZoomPlan
import com.studentanalysis.zoomcapture.zoom.ZoomPlanner

/** プリセットの撮影方式に合わせて、ズームの計画を立てる */
fun Preset.zoomPlan(cap: ZoomCapability): ZoomPlan =
    if (captureMode == CaptureMode.MAX_RESOLUTION && cap.maxResolutionArraySize != null) {
        ZoomPlanner.planMaxResolution(targetRect, cap)
    } else {
        ZoomPlanner.plan(targetRect, cap)
    }
