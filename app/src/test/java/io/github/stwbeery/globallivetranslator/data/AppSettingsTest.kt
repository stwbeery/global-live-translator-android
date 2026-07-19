package io.github.stwbeery.globallivetranslator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSettingsTest {
    @Test
    fun validSystemRoutingSettingsPass() {
        assertNull(AppSettings(apiKey = "test-key").validate())
    }

    @Test
    fun manualProxyRequiresValidHostAndPort() {
        assertEquals(
            "请输入代理地址",
            AppSettings(apiKey = "key", proxyMode = ProxyMode.HTTP, proxyHost = "").validate(),
        )
        assertEquals(
            "代理端口必须在 1-65535 之间",
            AppSettings(apiKey = "key", proxyMode = ProxyMode.SOCKS, proxyPort = 0).validate(),
        )
    }

    @Test
    fun vadThresholdIsBounded() {
        assertEquals("语音阈值必须在 -80 到 -10 dB 之间", AppSettings(apiKey = "key", vadThresholdDb = -5f).validate())
    }

    @Test
    fun overlayAppearanceDefaultsAreValid() {
        val settings = AppSettings(apiKey = "key")

        assertEquals(21f, settings.overlayFontSizeSp)
        assertEquals(0.85f, settings.overlayBackgroundOpacity)
        assertEquals(true, settings.overlayPositionLocked)
        assertNull(settings.validate())
    }

    @Test
    fun overlayFontSizeIsBounded() {
        assertEquals(
            "悬浮字幕字号必须在 14 到 36 sp 之间",
            AppSettings(apiKey = "key", overlayFontSizeSp = 13f).validate(),
        )
        assertEquals(
            "悬浮字幕字号必须在 14 到 36 sp 之间",
            AppSettings(apiKey = "key", overlayFontSizeSp = 37f).validate(),
        )
    }

    @Test
    fun overlayBackgroundOpacityIsBounded() {
        assertEquals(
            "悬浮字幕背景不透明度必须在 15% 到 95% 之间",
            AppSettings(apiKey = "key", overlayBackgroundOpacity = 0.14f).validate(),
        )
        assertEquals(
            "悬浮字幕背景不透明度必须在 15% 到 95% 之间",
            AppSettings(apiKey = "key", overlayBackgroundOpacity = 0.96f).validate(),
        )
    }
}
