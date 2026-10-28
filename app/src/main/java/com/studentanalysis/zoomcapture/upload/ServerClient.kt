package com.studentanalysis.zoomcapture.upload

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 研究室サーバーの受信 API を OkHttp で呼ぶ(Android に依存しないので JVM のテストでも使える) */
class ServerClient(private val config: ServerConfig, baseClient: OkHttpClient = OkHttpClient()) : UploadApi {

    private val trust = PinnedTrustManager(config.pin)
    private val client = baseClient.newBuilder()
        .sslSocketFactory(trust.socketFactory(), trust)
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val base: HttpUrl = config.url.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun health(): HealthInfo =
        call(Request.Builder().url(url("health")).get()).use { json.decodeFromString(it.bodyString()) }

    override suspend fun putSessionJson(sessionId: String, bytes: ByteArray) {
        call(Request.Builder().url(url("sessions", sessionId, "session.json")).put(bytes.toRequestBody(JSON))).close()
    }

    override suspend fun manifest(sessionId: String): Manifest? {
        val response = call(Request.Builder().url(url("sessions", sessionId, "manifest")).get(), allow404 = true)
        return response.use { if (it.code == 404) null else json.decodeFromString<Manifest>(it.bodyString()) }
    }

    override suspend fun putFile(sessionId: String, path: String, file: File, sha256: String) {
        val segments = listOf("sessions", sessionId, "files") + path.split('/')
        val request = Request.Builder()
            .url(url(*segments.toTypedArray()))
            .header("X-Content-SHA256", sha256)
            .put(file.asRequestBody(OCTET))
        call(request).close()
    }

    override suspend fun appendCaptures(sessionId: String, offset: Long, bytes: ByteArray): Long {
        val request = Request.Builder()
            .url(url("sessions", sessionId, "captures"))
            .header("X-Offset", offset.toString())
            .post(bytes.toRequestBody(CSV))
        return call(request).use { r ->
            json.parseToJsonElement(r.bodyString()).jsonObject["captures_size"]?.jsonPrimitive?.longOrNull
                ?: throw UploadException("サーバーの応答が不正です")
        }
    }

    override suspend fun finalize(sessionId: String) {
        call(Request.Builder().url(url("sessions", sessionId, "finalize")).post(ByteArray(0).toRequestBody(JSON))).close()
    }

    private fun url(vararg segments: String): HttpUrl =
        base.newBuilder().addPathSegment("api").apply { segments.forEach { addPathSegment(it) } }.build()

    /** 成功(2xx)以外は UploadException にする。読み終えたら呼び出し側で close する */
    private suspend fun call(builder: Request.Builder, allow404: Boolean = false): Response {
        val request = builder.header("Authorization", "Bearer ${config.token}").build()
        val response = try {
            client.newCall(request).await()
        } catch (e: SSLPeerUnverifiedException) {
            throw UploadException("サーバーの証明書が接続先 (${base.host}) 用ではありません。サーバーの init --host を確認してください", e)
        } catch (e: SSLException) {
            val cert = generateSequence(e as Throwable) { it.cause }.firstOrNull { it is CertificateException }
            throw UploadException(cert?.message ?: "TLS の接続に失敗しました: ${e.message}", e)
        } catch (e: IOException) {
            throw UploadException("サーバーに接続できません: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (response.isSuccessful || (allow404 && response.code == 404)) return response
        val body = response.use { runCatching { it.bodyString() }.getOrDefault("") }
        val detail = runCatching { json.parseToJsonElement(body).jsonObject["detail"] }.getOrNull()
        val message = runCatching { detail?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: runCatching { detail?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: "HTTP ${response.code}"
        throw when (response.code) {
            401 -> AuthException("サーバーに拒否されました: $message")
            507 -> ServerFullException(message)
            409 -> runCatching { detail?.jsonObject?.get("captures_size")?.jsonPrimitive?.longOrNull }.getOrNull()
                ?.let { OffsetConflictException(it) }
                ?: UploadException("サーバーにすでに別の内容があります: $message")
            else -> UploadException("サーバーのエラー (${response.code}): $message")
        }
    }

    private fun Response.bodyString(): String = body?.string() ?: ""

    private companion object {
        val JSON = "application/json".toMediaType()
        val CSV = "text/csv; charset=utf-8".toMediaType()
        val OCTET = "application/octet-stream".toMediaType()
    }
}

/** コルーチンが取り消されたら通信も取り消す */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
