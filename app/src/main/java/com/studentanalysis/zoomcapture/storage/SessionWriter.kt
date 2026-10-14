package com.studentanalysis.zoomcapture.storage

import android.content.Context
import com.studentanalysis.zoomcapture.capability.CapabilityReport
import com.studentanalysis.zoomcapture.capability.SizeI
import com.studentanalysis.zoomcapture.preset.Preset
import com.studentanalysis.zoomcapture.zoom.ZoomPlan
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class PresetRecord(val preset: Preset, val plan: ZoomPlan)

/** session.json の内容 */
@Serializable
data class SessionInfo(
    val appVersion: String,
    val presetSetName: String,
    val capability: CapabilityReport,
    val startWallTime: String,
    val endWallTime: String? = null,
    val stopReason: String? = null,
    val periodSec: Double,
    val stepSec: Double,
    val jpegQuality: Int,
    val stillSize: SizeI,
    val presets: List<PresetRecord>,
    val warnings: List<String> = emptyList(),
    val shotCount: Long = 0,
)

/** セッションフォルダの作成と session.json の書き出し */
class SessionWriter(private val root: File) {

    fun createSessionDir(presetSetName: String, start: Date): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(start)
        val dir = File(root, "${stamp}_${sanitizeFileName(presetSetName)}")
        dir.mkdirs()
        return dir
    }

    fun presetDir(sessionDir: File, preset: Preset): File =
        File(sessionDir, "${preset.id}_${sanitizeFileName(preset.name)}").apply { mkdirs() }

    /** 一時ファイルに書いてから置き換え、書き込み途中で落ちても壊れないようにする */
    fun writeInfo(sessionDir: File, info: SessionInfo) {
        val target = File(sessionDir, SESSION_JSON)
        val tmp = File(sessionDir, "$SESSION_JSON.tmp")
        tmp.writeText(AppJson.encodeToString(SessionInfo.serializer(), info))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        const val SESSION_JSON = "session.json"
        const val CAPTURES_CSV = "captures.csv"

        fun sessionsRoot(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply { mkdirs() }

        fun readInfo(sessionDir: File): SessionInfo? = runCatching {
            AppJson.decodeFromString<SessionInfo>(File(sessionDir, SESSION_JSON).readText())
        }.getOrNull()
    }
}
