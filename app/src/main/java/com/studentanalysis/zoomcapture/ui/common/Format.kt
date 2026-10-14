package com.studentanalysis.zoomcapture.ui.common

import com.studentanalysis.zoomcapture.zoom.ZoomPlan

fun formatBytes(bytes: Double): String = when {
    bytes >= 1e9 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1e6 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1e3 -> "%.0f KB".format(bytes / 1e3)
    else -> "%.0f B".format(bytes)
}

fun formatBytes(bytes: Long): String = formatBytes(bytes.toDouble())

fun formatDuration(seconds: Double): String {
    if (seconds.isInfinite()) return "∞"
    val s = seconds.toLong().coerceAtLeast(0)
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

/** 例: "4.0x(光学 3.0x × デジタル 1.3x)" */
fun describeZoom(plan: ZoomPlan): String {
    val total = "%.1fx".format(plan.totalZoom)
    return if (plan.opticalZoom > 1.01f || plan.opticalZoom < 0.99f) {
        "$total(光学 %.1fx × デジタル %.1fx)".format(plan.opticalZoom, plan.digitalZoom)
    } else {
        "$total(デジタル)"
    }
}
