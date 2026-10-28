package com.studentanalysis.zoomcapture.upload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** セッションごとの送信状態。撮影データのフォルダは汚さず、アプリ内部の領域に置く */
@Serializable
data class SessionUploadState(
    /** どのサーバー・端末名に向けた状態か(ServerConfig.key)。変わったら最初から送り直す */
    val serverKey: String? = null,
    /** 「端末のみ」で撮ったセッションを、あとから手動で送るよう指示された */
    val requested: Boolean = false,
    val sessionJsonSha: String? = null,
    val sentRows: Long = 0,
    val totalRows: Long = 0,
    val finalized: Boolean = false,
    /** 送信後に端末の画像を削除した */
    val localDeleted: Boolean = false,
    val lastError: String? = null,
    val updatedAtMs: Long = 0,
) {
    fun forServer(key: String): SessionUploadState =
        if (serverKey == key) this else SessionUploadState(serverKey = key, requested = requested)
}

class UploadStateStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun load(sessionId: String): SessionUploadState =
        runCatching { json.decodeFromString<SessionUploadState>(file(sessionId).readText()) }.getOrNull()
            ?: SessionUploadState()

    @Synchronized
    fun save(sessionId: String, state: SessionUploadState) {
        val target = file(sessionId)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.encodeToString(SessionUploadState.serializer(), state))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    @Synchronized
    fun update(sessionId: String, change: (SessionUploadState) -> SessionUploadState): SessionUploadState =
        change(load(sessionId)).also { save(sessionId, it) }

    @Synchronized
    fun delete(sessionId: String) {
        file(sessionId).delete()
    }

    private fun file(sessionId: String) = File(dir, "$sessionId.json")
}
