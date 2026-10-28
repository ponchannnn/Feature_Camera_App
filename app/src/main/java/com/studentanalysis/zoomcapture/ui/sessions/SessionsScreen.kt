package com.studentanalysis.zoomcapture.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studentanalysis.zoomcapture.ui.AppViewModel
import com.studentanalysis.zoomcapture.ui.Screen
import com.studentanalysis.zoomcapture.ui.SessionSummary
import com.studentanalysis.zoomcapture.ui.common.Warning
import com.studentanalysis.zoomcapture.ui.common.formatBytes
import com.studentanalysis.zoomcapture.upload.StorageMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(vm: AppViewModel) {
    BackHandler { vm.navigate(Screen.HOME) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf<List<SessionSummary>?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    val upload by vm.uploadStatus.collectAsState()
    LaunchedEffect(reload) {
        // 送信の進み具合を表示するため、定期的に読み直す
        while (true) {
            sessions = vm.listSessions()
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navigate(Screen.HOME) }) { Text("← 戻る") }
            Text("セッション一覧", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "PC へは USB で接続し、次のフォルダをコピーしてください: ${vm.sessionsRootPath}" +
                (upload.server?.let { "(サーバー ${it.url} に送ったセッションは、サーバーの sessions/${it.device}/ にあります)" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        val list = sessions
        when {
            list == null -> Text("読み込んでいます…")
            list.isEmpty() -> Text("まだセッションはありません")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.dir.path }) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.dir.name, fontWeight = FontWeight.Bold)
                                val info = s.info
                                Text(
                                    "撮影 ${info?.shotCount ?: "-"} 回 / ログ ${s.rows} 行 / ${formatBytes(s.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "開始 ${info?.startWallTime ?: "-"} / 終了 ${info?.endWallTime ?: "(記録なし: 途中で終了した可能性があります)"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                info?.stopReason?.let { Text("停止理由: $it", style = MaterialTheme.typography.bodySmall) }
                                val mode = StorageMode.fromName(info?.storageMode)
                                val u = s.upload
                                val uploadText = when {
                                    u.finalized && u.localDeleted -> "サーバー: 送信完了(端末の画像は削除済み)"
                                    u.finalized -> "サーバー: 送信完了"
                                    mode.usesServer || u.requested -> "サーバー: 送信中 ${u.sentRows} / ${u.totalRows} 件"
                                    else -> "サーバー: 送っていません(端末のみ)"
                                }
                                Text(uploadText, style = MaterialTheme.typography.bodySmall)
                                u.lastError?.takeIf { !u.finalized }?.let {
                                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = Warning)
                                }
                            }
                            if (!s.upload.finalized && !StorageMode.fromName(s.info?.storageMode).usesServer && !s.upload.requested) {
                                TextButton(onClick = {
                                    vm.requestUpload(s.dir)
                                    reload++
                                }, enabled = upload.server != null) { Text("サーバーへ送る") }
                            }
                            TextButton(onClick = { deleting = s }) { Text("削除") }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("セッションを削除しますか?") },
            text = { Text("${target.dir.name} の画像とログ(${formatBytes(target.sizeBytes)})を削除します。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        vm.deleteSession(target.dir)
                        reload++
                    }
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}
