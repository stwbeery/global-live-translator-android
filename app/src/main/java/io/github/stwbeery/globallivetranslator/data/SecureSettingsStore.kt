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
        overlayFontSizeSp = preferences.getFloat(KEY_OVERLAY_FONT_SIZE_SP, 21f),
        overlayBackgroundOpacity = preferences.getFloat(KEY_OVERLAY_BACKGROUND_OPACITY, 0.85f),
        overlayPositionLocked = preferences.getBoolean(KEY_OVERLAY_POSITION_LOCKED, true),
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
            .putFloat(KEY_OVERLAY_FONT_SIZE_SP, settings.overlayFontSizeSp)
            .putFloat(KEY_OVERLAY_BACKGROUND_OPACITY, settings.overlayBackgroundOpacity)
            .putBoolean(KEY_OVERLAY_POSITION_LOCKED, settings.overlayPositionLocked)
            .putFloat(KEY_VAD_THRESHOLD_DB, settings.vadThresholdDb)
            .apply()
    }

    fun saveOverlayAppearance(fontSizeSp: Float, backgroundOpacity: Float, positionLocked: Boolean) {
        require(fontSizeSp.isFinite() && fontSizeSp in 14f..36f) {
            "悬浮字幕字号必须在 14 到 36 sp 之间"
        }
        require(backgroundOpacity.isFinite() && backgroundOpacity in 0.15f..0.95f) {
            "悬浮字幕背景不透明度必须在 15% 到 95% 之间"
        }
        preferences.edit()
            .putFloat(KEY_OVERLAY_FONT_SIZE_SP, fontSizeSp)
            .putFloat(KEY_OVERLAY_BACKGROUND_OPACITY, backgroundOpacity)
            .putBoolean(KEY_OVERLAY_POSITION_LOCKED, positionLocked)
            .apply()
    }

    fun loadOverlayPosition(): OverlayPosition? {
        if (!preferences.contains(KEY_OVERLAY_POSITION_X) ||
            !preferences.contains(KEY_OVERLAY_POSITION_Y)
        ) {
            return null
        }
        val position = OverlayPosition(
            xFraction = preferences.getFloat(KEY_OVERLAY_POSITION_X, 0f),
            yFraction = preferences.getFloat(KEY_OVERLAY_POSITION_Y, 0f),
        )
        return position.takeIf {
            it.xFraction.isFinite() && it.xFraction in 0f..1f &&
                it.yFraction.isFinite() && it.yFraction in 0f..1f
        }
    }

    fun saveOverlayPosition(position: OverlayPosition) {
        require(position.xFraction.isFinite() && position.xFraction in 0f..1f) {
            "悬浮字幕横向位置必须在 0 到 1 之间"
        }
        require(position.yFraction.isFinite() && position.yFraction in 0f..1f) {
            "悬浮字幕纵向位置必须在 0 到 1 之间"
        }
        preferences.edit()
            .putFloat(KEY_OVERLAY_POSITION_X, position.xFraction)
            .putFloat(KEY_OVERLAY_POSITION_Y, position.yFraction)
            .apply()
    }

    fun clearOverlayPosition() {
        preferences.edit()
            .remove(KEY_OVERLAY_POSITION_X)
            .remove(KEY_OVERLAY_POSITION_Y)
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
        const val KEY_OVERLAY_FONT_SIZE_SP = "overlay_font_size_sp"
        const val KEY_OVERLAY_BACKGROUND_OPACITY = "overlay_background_opacity"
        const val KEY_OVERLAY_POSITION_LOCKED = "overlay_position_locked"
        const val KEY_OVERLAY_POSITION_X = "overlay_position_x"
        const val KEY_OVERLAY_POSITION_Y = "overlay_position_y"
        const val KEY_VAD_THRESHOLD_DB = "vad_threshold_db"
    }
}
