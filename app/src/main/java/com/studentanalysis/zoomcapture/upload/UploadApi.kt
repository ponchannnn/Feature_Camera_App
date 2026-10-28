package com.studentanalysis.zoomcapture.upload

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

@Serializable
data class RemoteFile(val path: String, val size: Long, val sha256: String)

@Serializable
data class Manifest(
    val captures_size: Long,
    val finalized: Boolean,
    val files: List<RemoteFile>,
)

@Serializable
data class HealthInfo(val status: String, val device: String, val free_bytes: Long, val accepting: Boolean)

/** サーバーとの通信で起きたエラー。message は画面にそのまま出せる文 */
open class UploadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** トークンが無効(サーバーで無効にされた、など)。設定し直すまで送信しない */
class AuthException(message: String) : UploadException(message)

/** サーバーの空き容量が足りない */
class ServerFullException(message: String) : UploadException(message)

/** captures.csv の位置がサーバーと合わない。serverSize から送り直す */
class OffsetConflictException(val serverSize: Long) : UploadException("captures.csv の位置がサーバーと一致しません")

/** 研究室サーバーの受信 API。テストでは偽物に差し替える */
interface UploadApi {
    suspend fun health(): HealthInfo

    suspend fun putSessionJson(sessionId: String, bytes: ByteArray)

    /** セッションが登録されていなければ null */
    suspend fun manifest(sessionId: String): Manifest?

    suspend fun putFile(sessionId: String, path: String, file: File, sha256: String)

    /** 追記後の captures.csv のサイズを返す */
    suspend fun appendCaptures(sessionId: String, offset: Long, bytes: ByteArray): Long

    suspend fun finalize(sessionId: String)
}
