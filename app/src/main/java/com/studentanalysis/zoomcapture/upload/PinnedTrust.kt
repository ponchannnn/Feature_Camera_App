package com.studentanalysis.zoomcapture.upload

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * 研究室サーバーの自己署名の証明書を、公開鍵のフィンガープリントで確認する。
 * 端末に CA 証明書を入れずに済み、フィンガープリントが一致しないサーバーには接続しない。
 * ホスト名(IP アドレス)の確認は OkHttp の標準の確認に任せる(サーバーの証明書の SAN に IP アドレスが入っている)。
 *
 * lint の CustomX509TrustManager は「検証を無効にしていないか」の警告。ここでは公開鍵を必ず比べ、一致しなければ例外にしている。
 */
@SuppressLint("CustomX509TrustManager")
class PinnedTrustManager(private val expectedPin: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("サーバーが証明書を送ってきませんでした")
        val actual = pinOf(leaf)
        if (actual != expectedPin) {
            throw CertificateException("サーバーの証明書が登録時と一致しません(登録 ${expectedPin.take(12)}…, 実際 ${actual.take(12)}…)")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("クライアント証明書は扱いません")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrustManager), null) }.socketFactory

    companion object {
        fun pinOf(cert: X509Certificate): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
    }
}
