package com.studentanalysis.zoomcapture.ui.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studentanalysis.zoomcapture.monitor.DeviceMonitor
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen
import com.studentanalysis.zoomcapture.ui.common.CameraPreview
import com.studentanalysis.zoomcapture.ui.common.ErrorRed
import com.studentanalysis.zoomcapture.ui.common.Thumbnail
import com.studentanalysis.zoomcapture.ui.common.Warning
import com.studentanalysis.zoomcapture.ui.common.formatBytes
import com.studentanalysis.zoomcapture.ui.common.formatDuration
import com.studentanalysis.zoomcapture.zoom.CoordinateMapper
import kotlinx.coroutines.delay

@Composable
fun CaptureScreen(vm: AppViewModel) {
    val cap by vm.capability.collectAsState()
    val set by vm.selectedSet.collectAsState()
    val cameraState by vm.cameraState.collectAsState()
    val progress by vm.captureProgress.collectAsState()
    val status by vm.deviceStatus.collectAsState()
    val dimmed by vm.dimmed.collectAsState()
    val upload by vm.uploadStatus.collectAsState()
    val c = cap ?: return
    val s = set ?: return

    KeepScreenOn(dimmed)
    // 撮影中は戻るボタンで抜けない(長押しの停止ボタンで止める)
    BackHandler(enabled = progress.running) {}
    BackHandler(enabled = !progress.running) { vm.navigate(Screen.HOME) }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    val displayRotation by vm.displayRotationDegrees.collectAsState()
    val rotation = remember(displayRotation, c) { vm.rotation }
    Row(Modifier.fillMaxSize()) {
        CameraPreview(
            previewSize = c.previewSize(),
            displayAspect = CoordinateMapper.displayAspect(c.activeArraySize.aspect.toFloat(), rotation),
            cameraState = cameraState,
            onSurface = vm::onPreviewSurface,
            modifier = Modifier.weight(0.55f).fillMaxHeight().background(Color.Black),
        ) {
            val current = s.presets.firstOrNull { it.id == progress.currentPresetId }
            if (current != null) {
                Text(
                    current.name,
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x88000000)).padding(4.dp),
                    color = Color.White,
                )
            }
        }

        Column(
            Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val title = when {
                progress.running -> "● 撮影中"
                progress.stopReason != null -> "停止しました(${progress.stopReason!!.label})"
                else -> "開始を待っています…"
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = if (progress.running) ErrorRed else MaterialTheme.colorScheme.onBackground,
            )
            val elapsedSec = progress.startElapsedMs?.let { ((now - it) / 1000.0).coerceAtLeast(0.0) } ?: 0.0
            Text("経過 ${formatDuration(elapsedSec)} / 撮影 ${progress.shots} 回")
            Text("成功 ${progress.ok}  遅延 ${progress.late}  スキップ ${progress.skipped}  失敗 ${progress.errors}")

            val remaining = vm.storageEstimate(s, status)?.secondsRemaining ?: Double.POSITIVE_INFINITY
            Text("保存 ${formatBytes(progress.bytesWritten)} / 空き ${formatBytes(status.freeBytes)}(あと約 ${formatDuration(remaining)})")
            if (remaining < 15 * 60) Text("⚠ 空き容量が少なくなっています", color = Warning)

            Text(
                "バッテリー ${status.batteryPercent ?: "-"}%" + (if (status.charging) "(充電中)" else "") +
                    " / 温度 ${DeviceMonitor.thermalLabel(status.thermalStatus)}",
                color = if (status.thermalSevere || !status.charging) Warning else MaterialTheme.colorScheme.onBackground,
            )
            if (progress.thermalWarning) Text("⚠ 撮影中に端末の温度が非常に高くなりました", color = Warning)
            progress.message?.let { Text(it, color = Warning, style = MaterialTheme.typography.bodySmall) }
            upload.live?.let { u ->
                Text(
                    "サーバー送信 ${u.sentRows} / ${u.totalRows} 件" +
                        (if (u.finalized) "(完了)" else "") + (upload.pausedReason?.let { "(${it})" } ?: ""),
                )
                upload.lastError?.let { Text("⚠ 送信エラー: $it(端末には保存しています)", color = Warning, style = MaterialTheme.typography.bodySmall) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.enabledPresets.forEach { p ->
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Thumbnail(progress.lastFiles[p.id], Modifier.fillMaxWidth(), maxSide = 320)
                    }
                }
            }

            OutlinedButton(onClick = { vm.setDimmed(!dimmed) }) {
                Text(if (dimmed) "明るさを戻す" else "画面を暗くする(省電力)")
            }

            if (progress.running) {
                StopButton(onStop = vm::stopCapture)
            } else if (progress.stopReason != null) {
                progress.sessionDir?.let { Text("保存先: $it", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { vm.navigate(Screen.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("ホームに戻る") }
            }
        }
    }
}

/** 誤タップを防ぐため、長押しで停止する */
@Composable
private fun StopButton(onStop: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(ErrorRed)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onStop() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text("長押しで停止", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** 撮影画面にいる間は画面をオンのまま保ち、必要なら暗くする */
@Composable
private fun KeepScreenOn(dimmed: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    LaunchedEffect(dimmed) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (dimmed) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
