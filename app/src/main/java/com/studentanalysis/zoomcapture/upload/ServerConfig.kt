package com.studentanalysis.zoomcapture.upload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 撮影データの保存先。セッションの開始時に決め、session.json に記録する */
enum class StorageMode(val label: String, val description: String) {
    LOCAL_ONLY("端末のみ", "端末に保存します。PC へは USB でコピーします"),
    LOCAL_AND_SERVER("端末+サーバー", "端末に保存し、撮影しながらサーバーにも送ります。送った後も端末に残します"),
    SERVER_DELETE_LOCAL(
        "サーバー(送信後に端末から削除)",
        "端末に保存してサーバーに送り、セッションの全ファイルをサーバーが受け取ったことを確認してから端末の画像を削除します",
    ),
    ;

    val usesServer: Boolean get() = this != LOCAL_ONLY

    companion object {
        fun fromName(name: String?): StorageMode = entries.firstOrNull { it.name == name } ?: LOCAL_ONLY
    }
}

/** 研究室サーバーへの接続設定。サーバーの add-device が表示する QR コードの内容 */
@Serializable
data class ServerConfig(
    val url: String,
    val device: String,
    val token: String,
    /** サーバーの証明書の公開鍵(SubjectPublicKeyInfo)の SHA-256 を Base64 にしたもの */
    val pin: String,
) {
    /** 送信状態の記録を、サーバーや端末名が変わったときに作り直すためのキー */
    val key: String get() = "$url|$device"

    val pinShort: String get() = pin.take(12) + "…"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class Payload(val v: Int = 1, val url: String, val device: String, val token: String, val pin: String)

        /** QR コード(または貼り付けた文字列)を読む。読めなければ理由を返す */
        fun parsePairing(text: String): Result<ServerConfig> = runCatching {
            val p = try {
                json.decodeFromString<Payload>(text.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("サーバーの設定用の QR コードではありません")
            }
            require(p.v == 1) { "対応していない形式です(v=${p.v})。アプリを更新してください" }
            val url = p.url.trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("URL が不正です: ${p.url}")
            require(url.isHttps) { "https の URL だけを使えます" }
            require(p.token.isNotBlank() && p.device.isNotBlank()) { "トークンまたは端末名がありません" }
            require(p.pin.length == 44 && p.pin.endsWith("=")) { "証明書のフィンガープリントが不正です" }
            ServerConfig(p.url.trimEnd('/'), p.device, p.token, p.pin)
        }
    }
}
