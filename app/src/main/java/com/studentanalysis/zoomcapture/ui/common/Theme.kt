package com.studentanalysis.zoomcapture.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFC107)
val Warning = Color(0xFFFFB74D)
val ErrorRed = Color(0xFFEF5350)

@Composable
fun ZoomCaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            secondary = Color(0xFF90CAF9),
            background = Color(0xFF101214),
            surface = Color(0xFF181B1F),
            error = ErrorRed,
        ),
        content = content,
    )
}
