package com.studentanalysis.zoomcapture.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.studentanalysis.zoomcapture.preset.PresetSet
import com.studentanalysis.zoomcapture.schedule.ScheduleValidator
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen
import com.studentanalysis.zoomcapture.ui.common.ErrorRed
import com.studentanalysis.zoomcapture.ui.common.Warning
import com.studentanalysis.zoomcapture.ui.common.describeZoom
import com.studentanalysis.zoomcapture.ui.common.formatBytes
import com.studentanalysis.zoomcapture.ui.common.formatDuration
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: AppViewModel) {
    val set by vm.selectedSet.collectAsState()
    val sets by vm.sets.collectAsState()
    val status by vm.deviceStatus.collectAsState()
    val upload by vm.uploadStatus.collectAsState()
    val current = set

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("講義ズーム撮影", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.navigate(Screen.SESSIONS) }) { Text("セッション一覧") }
                TextButton(onClick = { vm.navigate(Screen.DEVICE_INFO) }) { Text("端末情報") }
            }
            PresetSetSelector(sets, current, onSelect = vm::selectSet, onCreate = vm::createSet, onDelete = vm::deleteSet)
            HorizontalDivider()
            if (current != null) {
                Text("プリセット(撮影順)", fontWeight = FontWeight.Bold)
                current.presets.forEachIndexed { i, p ->
                    val plan = vm.planFor(p)
                    val zoom = plan?.let(::describeZoom) ?: "-"
                    val latency = p.measuredLatencyMs?.let { "所要 ${it}ms" } ?: "未計測"
                    Text(
                        "${i + 1}. ${p.name}  $zoom  ${p.captureMode.label}  $latency" + if (p.enabled) "" else "(無効)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(onClick = { vm.navigate(Screen.EDITOR) }) { Text("プリセットを編集") }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (current == null) {
                Text("プリセットセットを読み込んでいます…")
                return@Column
            }
            ScheduleEditor(current, onChange = vm::updateSchedule)

            val check = vm.scheduleCheck(current)
            check.errors.forEach { Text("✕ $it", color = ErrorRed) }
            check.warnings.forEach { Text("⚠ $it", color = Warning) }
            val minStep = ScheduleValidator.measuredMinStepMs(current.enabledPresets.map { it.measuredLatencyMs })
            if (minStep != null && current.stepSec * 1000 < minStep) {
                val step = ceil(minStep / 100.0) / 10.0
                OutlinedButton(onClick = {
                    vm.updateSchedule(stepSec = step, periodSec = maxOf(current.periodSec, step * current.enabledPresets.size))
                }) { Text("s を下限 (%.1f 秒) に合わせる".format(step)) }
            }

            HorizontalDivider()
            vm.storageEstimate(current, status)?.let { e ->
                Text("容量の見積もり", fontWeight = FontWeight.Bold)
                Text("1秒あたり ${formatBytes(e.bytesPerSecond)} / 100分で ${formatBytes(e.bytesPerLecture)}")
                Text("空き容量 ${formatBytes(e.freeBytes)}(撮影できる時間の目安 ${formatDuration(e.secondsRemaining)})")
                if (e.secondsRemaining < AppViewModel.LECTURE_SECONDS) {
                    Text("⚠ 空き容量が100分の撮影に足りません", color = Warning)
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存先: ${upload.mode.label}", fontWeight = FontWeight.Bold)
                    upload.server?.takeIf { upload.mode.usesServer }?.let {
                        Text("${it.url}(端末名 ${it.device})", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = { vm.navigate(Screen.SERVER) }) { Text("保存先の設定") }
            }
            if (upload.mode.usesServer && upload.server == null) {
                Text("⚠ サーバーが設定されていないため、端末にだけ保存します", color = Warning)
            }
            if (upload.mode.usesServer) {
                Text(
                    "撮影中も画像はいったん端末に保存するので、端末の空き容量は上の見積もりのとおり必要です",
                    style = MaterialTheme.typography.bodySmall,
                )
                upload.lastError?.let { Text("⚠ 送信エラー: $it", color = Warning, style = MaterialTheme.typography.bodySmall) }
                if (upload.pendingSessions > 0) {
                    Text("送り終えていないセッション: ${upload.pendingSessions} 件", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "バッテリー ${status.batteryPercent ?: "-"}%" + if (status.charging) "(充電中)" else "(充電していません)",
                color = if (status.charging) MaterialTheme.colorScheme.onBackground else Warning,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::startCapture,
                enabled = check.ok,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("撮影開始", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun PresetSetSelector(
    sets: List<PresetSet>,
    current: PresetSet?,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Boolean,
    onDelete: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("プリセットセット")
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(current?.name ?: "-") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                sets.forEach { s ->
                    DropdownMenuItem(text = { Text(s.name) }, onClick = { onSelect(s.name); menu = false })
                }
            }
        }
        TextButton(onClick = { creating = true }) { Text("新規") }
        TextButton(onClick = { deleting = true }, enabled = current != null) { Text("削除") }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新しいプリセットセット") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("名前(講義室名など)") }, singleLine = true)
                    error?.let { Text(it, color = ErrorRed) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onCreate(name)) creating = false else error = "名前が空か、同じ名前のセットがあります"
                }) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("キャンセル") } },
        )
    }
    if (deleting && current != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("「${current.name}」を削除しますか?") },
            text = { Text("プリセットの設定が削除されます。撮影済みのセッションは削除されません。") },
            confirmButton = { TextButton(onClick = { onDelete(current.name); deleting = false }) { Text("削除") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun ScheduleEditor(set: PresetSet, onChange: (Double?, Double?, Int?) -> Unit) {
    Text("撮影設定", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField("撮影周期 T(秒)", set.periodSec, Modifier.weight(1f)) { onChange(it, null, null) }
        NumberField("プリセット間隔 s(秒)", set.stepSec, Modifier.weight(1f)) { onChange(null, it, null) }
    }
    Text(
        "各プリセットを T 秒ごとに撮影し、プリセットどうしは s 秒ずらします(T ≥ プリセット数 × s)",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("JPEG 画質 ${set.jpegQuality}", Modifier.width(120.dp))
        Slider(
            value = set.jpegQuality.toFloat(),
            onValueChange = { onChange(null, null, it.roundToInt()) },
            valueRange = 70f..100f,
            steps = 29,
        )
    }
}

@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier, onValid: (Double) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toDoubleOrNull()?.takeIf { v -> v > 0 }?.let(onValid)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
