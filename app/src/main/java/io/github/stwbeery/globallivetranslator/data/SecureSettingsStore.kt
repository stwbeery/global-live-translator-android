package io.github.stwbeery.globallivetranslator.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        apiKey = decryptApiKey(),
        targetLanguage = preferences.getString(KEY_TARGET_LANGUAGE, "zh-Hans") ?: "zh-Hans",
        proxyMode = runCatching {
            ProxyMode.valueOf(preferences.getString(KEY_PROXY_MODE, ProxyMode.SYSTEM.name)!!)
        }.getOrDefault(ProxyMode.SYSTEM),
        proxyHost = preferences.getString(KEY_PROXY_HOST, "127.0.0.1") ?: "127.0.0.1",
        proxyPort = preferences.getInt(KEY_PROXY_PORT, 7890),
        overlayEnabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, true),
        vadThresholdDb = preferences.getFloat(KEY_VAD_THRESHOLD_DB, -50f),
    )

    fun save(settings: AppSettings) {
        settings.validate()?.let { throw IllegalArgumentException(it) }
        val encryptedApiKey = encrypt(settings.apiKey.trim())
        preferences.edit()
            .putString(KEY_API_KEY, encryptedApiKey)
            .putString(KEY_TARGET_LANGUAGE, settings.targetLanguage.trim())
            .putString(KEY_PROXY_MODE, settings.proxyMode.name)
            .putString(KEY_PROXY_HOST, settings.proxyHost.trim())
            .putInt(KEY_PROXY_PORT, settings.proxyPort)
            .putBoolean(KEY_OVERLAY_ENABLED, settings.overlayEnabled)
            .putFloat(KEY_VAD_THRESHOLD_DB, settings.vadThresholdDb)
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(1 + cipher.iv.size + encrypted.size)
        payload[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(payload, destinationOffset = 1)
        encrypted.copyInto(payload, destinationOffset = 1 + cipher.iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptApiKey(): String {
        val encoded = preferences.getString(KEY_API_KEY, null) ?: return ""
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.isNotEmpty())
            val ivLength = payload[0].toInt() and 0xff
            require(ivLength in 12..16 && payload.size > 1 + ivLength)
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "secure_settings"
        const val KEY_ALIAS = "global_live_translator_settings_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_API_KEY = "api_key_encrypted"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_PROXY_MODE = "proxy_mode"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        const val KEY_VAD_THRESHOLD_DB = "vad_threshold_db"
    }
}
