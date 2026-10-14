package com.studentanalysis.zoomcapture.ui.preseteditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.preset.FocusMode
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.preset.PresetSet
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen
import com.studentanalysis.zoomcapture.ui.common.Accent
import com.studentanalysis.zoomcapture.ui.common.CameraPreview
import com.studentanalysis.zoomcapture.ui.common.Thumbnail
import com.studentanalysis.zoomcapture.ui.common.Warning
import com.studentanalysis.zoomcapture.ui.common.describeZoom
import com.studentanalysis.zoomcapture.zoom.CoordinateMapper
import com.studentanalysis.zoomcapture.zoom.NormRect
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PresetEditorScreen(vm: AppViewModel) {
    val cap by vm.capability.collectAsState()
    val set by vm.selectedSet.collectAsState()
    val cameraState by vm.cameraState.collectAsState()
    val c = cap ?: return
    val s = set ?: return
    var selectedId by remember { mutableStateOf(s.presets.firstOrNull()?.id) }
    if (selectedId != null && s.presets.none { it.id == selectedId }) selectedId = s.presets.firstOrNull()?.id

    BackHandler { vm.navigate(Screen.HOME) }

    val displayRotation by vm.displayRotationDegrees.collectAsState()
    val rotation = remember(displayRotation, c) { vm.rotation }
    Row(Modifier.fillMaxSize()) {
        CameraPreview(
            previewSize = c.previewSize(),
            displayAspect = CoordinateMapper.displayAspect(c.activeArraySize.aspect.toFloat(), rotation),
            cameraState = cameraState,
            onSurface = vm::onPreviewSurface,
            modifier = Modifier.weight(0.62f).fillMaxHeight().background(Color.Black),
        ) {
            PresetOverlay(vm, s, selectedId, rotation, onDrawn = { rect -> selectedId?.let { vm.setTargetRect(it, rect) } })
        }
        Column(
            Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.navigate(Screen.HOME) }) { Text("← 戻る") }
                Text("プリセット編集", style = MaterialTheme.typography.titleMedium)
            }
            Text("プレビュー上をドラッグして、撮りたい範囲を囲んでください", style = MaterialTheme.typography.bodySmall)
            PresetList(vm, s, selectedId, onSelect = { selectedId = it })
            HorizontalDivider()
            s.presets.firstOrNull { it.id == selectedId }?.let { PresetDetail(vm, c, it) }
        }
    }
}

/** 各プリセットの撮影範囲の表示と、ドラッグでの範囲指定 */
@Composable
private fun PresetOverlay(vm: AppViewModel, set: PresetSet, selectedId: String?, rotation: Int, onDrawn: (NormRect) -> Unit) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        Modifier.fillMaxSize().pointerInput(selectedId) {
            detectDragGestures(
                onDragStart = { dragStart = it; dragEnd = it },
                onDrag = { change, _ -> dragEnd = change.position },
                onDragEnd = {
                    val a = dragStart
                    val b = dragEnd
                    if (a != null && b != null && abs(a.x - b.x) > 8 && abs(a.y - b.y) > 8) {
                        val display = NormRect(a.x / size.width, a.y / size.height, b.x / size.width, b.y / size.height).normalized()
                        onDrawn(CoordinateMapper.displayRectToSensor(display, rotation))
                    }
                    dragStart = null
                    dragEnd = null
                },
                onDragCancel = { dragStart = null; dragEnd = null },
            )
        },
    ) {
        set.presets.filter { it.enabled }.forEach { p ->
            val plan = vm.planFor(p) ?: return@forEach
            val selected = p.id == selectedId
            val fitted = CoordinateMapper.sensorRectToDisplay(plan.fittedRect, rotation)
            val color = if (selected) Accent else Color.White.copy(alpha = 0.6f)
            drawNormRect(fitted, color, if (selected) 3.dp.toPx() else 1.5.dp.toPx())
            if (selected) {
                val requested = CoordinateMapper.sensorRectToDisplay(p.targetRect, rotation)
                drawNormRect(requested, Color.Cyan, 1.5.dp.toPx(), dashed = true)
            }
            drawText(
                textMeasurer,
                p.name,
                topLeft = Offset(fitted.left * size.width + 4.dp.toPx(), fitted.top * size.height + 2.dp.toPx()),
                style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, background = Color(0x88000000)),
            )
        }
        val a = dragStart
        val b = dragEnd
        if (a != null && b != null) {
            drawRect(
                Color.Cyan,
                topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                size = Size(abs(a.x - b.x), abs(a.y - b.y)),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawNormRect(r: NormRect, color: Color, width: Float, dashed: Boolean = false) {
    drawRect(
        color,
        topLeft = Offset(r.left * size.width, r.top * size.height),
        size = Size(r.width * size.width, r.height * size.height),
        style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null),
    )
}

@Composable
private fun PresetList(vm: AppViewModel, set: PresetSet, selectedId: String?, onSelect: (String) -> Unit) {
    set.presets.forEachIndexed { i, p ->
        Row(
            Modifier.fillMaxWidth()
                .background(if (p.id == selectedId) Color(0x33FFC107) else Color.Transparent)
                .clickable { onSelect(p.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = p.enabled, onCheckedChange = { vm.updatePreset(p.copy(enabled = it)) })
            Text("${i + 1}. ${p.name}", Modifier.weight(1f))
            TextButton(onClick = { vm.movePreset(p.id, -1) }, enabled = i > 0) { Text("↑") }
            TextButton(onClick = { vm.movePreset(p.id, 1) }, enabled = i < set.presets.lastIndex) { Text("↓") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.addPreset()?.let(onSelect) }) { Text("追加") }
        OutlinedButton(onClick = { selectedId?.let(vm::deletePreset) }, enabled = selectedId != null && set.presets.size > 1) {
            Text("削除")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDetail(vm: AppViewModel, cap: CapabilityReport, preset: Preset) {
    val testShot by vm.testShot.collectAsState()

    var name by remember(preset.id) { mutableStateOf(preset.name) }
    LaunchedEffect(preset.name) { if (name != preset.name) name = preset.name }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            if (it.isNotBlank()) vm.updatePreset(preset.copy(name = it))
        },
        label = { Text("名前") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    vm.planFor(preset)?.let { plan ->
        Text("ズーム: ${describeZoom(plan)}")
        plan.expectedLensId?.let { Text("使われる見込みのレンズ: $it", style = MaterialTheme.typography.bodySmall) }
        if (plan.clampedByMaxZoom) Text("⚠ 最大ズームでも、指定した範囲より広く写ります", color = Warning)
    }

    Text("撮影方式", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cap.supportedModes().forEach { mode ->
            FilterChip(
                selected = preset.captureMode == mode,
                onClick = { vm.updatePreset(preset.copy(captureMode = mode, measuredLatencyMs = null)) },
                label = { Text(mode.label) },
            )
        }
    }
    if (preset.captureMode == com.studentanalysis.zoomcapture.preset.CaptureMode.BURST) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("連写枚数 ${preset.burstCount}", Modifier.width(110.dp))
            Slider(
                value = preset.burstCount.toFloat(),
                onValueChange = { vm.updatePreset(preset.copy(burstCount = it.roundToInt(), measuredLatencyMs = null)) },
                valueRange = 2f..8f,
                steps = 5,
            )
        }
    }

    Text("ピント", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusMode.entries.forEach { mode ->
            FilterChip(
                selected = preset.focusMode == mode,
                enabled = mode != FocusMode.FIXED || cap.canFixFocus,
                onClick = { vm.updatePreset(preset.copy(focusMode = mode, measuredLatencyMs = null)) },
                label = { Text(mode.label) },
            )
        }
    }
    when {
        !cap.hasAutoFocus -> Text("この端末のカメラは固定焦点です", style = MaterialTheme.typography.bodySmall)
        !cap.canFixFocus -> Text("この端末はピント位置の固定に対応していないため、毎回 AF します", style = MaterialTheme.typography.bodySmall)
        preset.focusMode == FocusMode.FIXED -> Text(
            preset.fixedFocusDistance?.let { "固定するピント位置: %.2f(レンズ ${preset.fixedFocusPhysicalId ?: "-"})".format(it) }
                ?: "テスト撮影すると、AF で合わせたピント位置を記録します",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (cap.aeCompensationMax > cap.aeCompensationMin) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("露出補正 %+.1f EV".format(preset.exposureCompensation * cap.aeCompensationStep), Modifier.width(130.dp))
            Slider(
                value = preset.exposureCompensation.toFloat(),
                onValueChange = { vm.updatePreset(preset.copy(exposureCompensation = it.roundToInt())) },
                valueRange = cap.aeCompensationMin.toFloat()..cap.aeCompensationMax.toFloat(),
                steps = (cap.aeCompensationMax - cap.aeCompensationMin - 1).coerceAtLeast(0),
            )
        }
    }

    HorizontalDivider()
    Text("所要時間: " + (preset.measuredLatencyMs?.let { "${it}ms" } ?: "未計測(テスト撮影で計測します)"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.testShot(preset.id) }, enabled = !testShot.running) {
            Text(if (testShot.running) "撮影中…" else "テスト撮影")
        }
        if (cap.canFixFocus && preset.focusMode == FocusMode.FIXED) {
            OutlinedButton(onClick = { vm.testShot(preset.id, refocus = true) }, enabled = !testShot.running) {
                Text("ピントを取り直す")
            }
        }
    }
    if (testShot.presetId == preset.id) {
        testShot.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        testShot.imagePath?.let { Thumbnail(it, Modifier.fillMaxWidth(), key = testShot.version, maxSide = 1024) }
    }
}
