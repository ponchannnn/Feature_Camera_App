package com.studentanalysis.zoomcapture.ui.server

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen
import com.studentanalysis.zoomcapture.ui.common.ErrorRed
import com.studentanalysis.zoomcapture.ui.common.Warning
import com.studentanalysis.zoomcapture.ui.common.formatBytes
import com.studentanalysis.zoomcapture.upload.ServerConfig
import com.studentanalysis.zoomcapture.upload.StorageMode
import kotlinx.coroutines.launch

/** 保存先(端末 / サーバー)の選択と、研究室サーバーの接続設定 */
@Composable
fun ServerSettingsScreen(vm: AppViewModel) {
    BackHandler { vm.navigate(Screen.HOME) }
    val status by vm.uploadStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun test(config: ServerConfig) {
        testing = true
        testResult = null
        scope.launch {
            val r = vm.testConnection(config)
            testing = false
            testResult = r.fold(
                onSuccess = { h ->
                    val space = "サーバーの空き ${formatBytes(h.free_bytes)}"
                    if (h.accepting) true to "接続できました(端末名 ${h.device}、$space)"
                    else false to "接続できましたが、サーバーの空き容量が足りないため受け取りを停止しています($space)"
                },
                onFailure = { false to (it.message ?: it.toString()) },
            )
        }
    }

    fun apply(text: String): String? {
        val parsed = ServerConfig.parsePairing(text)
        val config = parsed.getOrElse { return it.message }
        vm.saveServer(config)
        test(config)
        return null
    }

    var scanError by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanError = apply(it) }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navigate(Screen.HOME) }) { Text("← 戻る") }
            Text("保存先の設定", style = MaterialTheme.typography.titleMedium)
        }

        Text("保存先", fontWeight = FontWeight.Bold)
        StorageMode.entries.forEach { mode ->
            val enabled = !mode.usesServer || status.server != null
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = status.mode == mode,
                    enabled = enabled,
                    onClick = { vm.setStorageMode(mode) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = status.mode == mode, onClick = null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(mode.label, color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline)
                    Text(mode.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (status.server == null) {
            Text("サーバーに送るには、先に下でサーバーを設定してください", style = MaterialTheme.typography.bodySmall, color = Warning)
        }
        Text(
            "どのモードでも、撮影した画像はまず端末に保存し、撮影のタイミングを優先して裏で送ります。" +
                "「送信後に端末から削除」でも、削除するのはセッションの撮影が終わり、全ファイルの受け取りを確認した後です。",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("研究室サーバー", fontWeight = FontWeight.Bold)
        val server = status.server
        if (server == null) {
            Text("未設定")
        } else {
            Text("接続先 ${server.url}")
            Text("端末名 ${server.device} / 証明書 ${server.pinShort}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "サーバーで `python -m zoomcapture_server add-device <端末名>` を実行すると、設定用の QR コードが表示されます。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scanError = null
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("サーバーに表示された QR コードを読み取ってください")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            }) { Text("QR コードを読み取る") }
            OutlinedButton(onClick = { pasting = true }) { Text("文字列を貼り付ける") }
        }
        scanError?.let { Text("✕ $it", color = ErrorRed) }
        if (server != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { test(server) }, enabled = !testing) { Text(if (testing) "確認中…" else "接続テスト") }
                TextButton(onClick = { confirmClear = true }) { Text("設定を削除") }
            }
        }
        testResult?.let { (ok, message) -> Text((if (ok) "✓ " else "✕ ") + message, color = if (ok) MaterialTheme.colorScheme.primary else ErrorRed) }
        status.lastError?.let { Text("最後の送信エラー: $it", color = Warning, style = MaterialTheme.typography.bodySmall) }
        Text(
            "接続できない場合は、教室の Wi-Fi から研究室サーバーの IP アドレスとポートに届くか(学内のファイアウォール)を確認してください。",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (pasting) {
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text("設定の文字列を貼り付け") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, label = { Text("add-device が表示した {\"v\":1,...}") }, minLines = 3)
                    error?.let { Text(it, color = ErrorRed) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    error = apply(text)
                    if (error == null) pasting = false
                }) { Text("設定") }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text("キャンセル") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("サーバーの設定を削除しますか?") },
            text = { Text("保存先は「端末のみ」に戻ります。送り終えていないセッションは、同じサーバー・端末名で設定し直すと続きから送ります。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearServer()
                    testResult = null
                    confirmClear = false
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("キャンセル") } },
        )
    }
}
