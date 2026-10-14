package com.studentanalysis.zoomcapture.ui.deviceinfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen

@Composable
fun DeviceInfoScreen(vm: AppViewModel) {
    BackHandler { vm.navigate(Screen.HOME) }
    val cap by vm.capability.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navigate(Screen.HOME) }) { Text("← 戻る") }
            Text("この端末のカメラ情報", style = MaterialTheme.typography.titleMedium)
        }
        cap?.let { DeviceInfoContent(it) }
    }
}

@Composable
private fun DeviceInfoContent(c: CapabilityReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section("端末")
        Item("機種", "${c.manufacturer} ${c.model}(Android API ${c.sdkInt})")
        Item("カメラ ID", "${c.cameraId}(ハードウェアレベル ${c.hardwareLevel})")

        Section("レンズとズーム")
        Item("論理マルチカメラ", yesNo(c.isLogicalMultiCamera))
        if (c.physicalLenses.isEmpty()) {
            Item("レンズ", "単一のレンズとして扱います")
        } else {
            c.physicalLenses.forEach { l ->
                Item(
                    "レンズ ${l.id}",
                    "%.2fx相当 / 焦点距離 %.2fmm / センサー幅 %.2fmm / %s".format(
                        l.equivalentZoom, l.focalLengthMm, l.sensorWidthMm, l.pixelArray,
                    ),
                )
            }
        }
        Item(
            "ズームの範囲",
            if (c.zoomRatioSupported) "%.2fx 〜 %.1fx(CONTROL_ZOOM_RATIO)".format(c.zoomRatioMin, c.zoomRatioMax)
            else "1.0x 〜 %.1fx(デジタルズームのみ)".format(c.maxDigitalZoom),
        )
        Item("ズーム切り替えの高速化", yesNo(c.settingsOverrideZoom))

        Section("撮影方式")
        Item("使える撮影方式", c.supportedModes().joinToString(" / ") { it.label })
        Item("通常の静止画", c.stillSize().toString())
        Item("最大解像度モード", c.maxResolutionStillSize()?.toString() ?: "非対応")
        Item("RAW", c.rawSize()?.toString() ?: "非対応")
        Item(
            "Camera Extensions",
            if (c.supportedExtensions.isEmpty()) "なし" else c.supportedExtensions.joinToString() + "(検出のみ。撮影には未対応)",
        )

        Section("ピント・露出")
        Item("ピント", if (!c.hasAutoFocus) "固定焦点" else if (c.canFixFocus) "AF / ピント位置の固定" else "AF のみ")
        Item("AF / AE の領域指定", "AF ${c.maxAfRegions} 個 / AE ${c.maxAeRegions} 個")
        Item("露出補正", "%d 〜 %d(1段 %.2f EV)".format(c.aeCompensationMin, c.aeCompensationMax, c.aeCompensationStep))

        Section("センサー")
        Item("有効画素領域", "${c.activeArraySize}" + (c.activeArrayMaxResolution?.let { "(最大解像度 ${it.width}x${it.height})" } ?: ""))
        Item("取り付け向き", "${c.sensorOrientation}°")
        Item("タイムスタンプ", if (c.timestampRealtime) "REALTIME" else "UNKNOWN(撮影時刻は端末の時計で代用)")
        Item("プレビュー", c.previewSize().toString())
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Item(label: String, value: String) {
    Row {
        Text(label, Modifier.width(200.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun yesNo(b: Boolean) = if (b) "あり" else "なし"
