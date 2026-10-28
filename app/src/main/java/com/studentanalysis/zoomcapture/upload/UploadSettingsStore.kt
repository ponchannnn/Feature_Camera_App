package com.studentanalysis.zoomcapture.upload

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 保存先のモードとサーバーの接続設定。トークンは Android Keystore の鍵で暗号化して保存する
 * (アプリ内部の領域にあり、自動バックアップの対象外だが、念のため平文では置かない)。
 */
class UploadSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("upload", Context.MODE_PRIVATE)

    var mode: StorageMode
        get() = StorageMode.fromName(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    fun loadServer(): ServerConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val device = prefs.getString(KEY_DEVICE, null) ?: return null
        val pin = prefs.getString(KEY_PIN, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return ServerConfig(url, device, token, pin)
    }

    fun saveServer(config: ServerConfig) {
        prefs.edit {
            putString(KEY_URL, config.url)
            putString(KEY_DEVICE, config.device)
            putString(KEY_PIN, config.pin)
            putString(KEY_TOKEN, encrypt(config.token))
        }
    }

    fun clearServer() {
        prefs.edit {
            remove(KEY_URL)
            remove(KEY_DEVICE)
            remove(KEY_PIN)
            remove(KEY_TOKEN)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(encrypted)
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(":", limit = 2).map { Base64.getDecoder().decode(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_MODE = "storage_mode"
        const val KEY_URL = "server_url"
        const val KEY_DEVICE = "server_device"
        const val KEY_PIN = "server_pin"
        const val KEY_TOKEN = "server_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zoomcapture_server_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
