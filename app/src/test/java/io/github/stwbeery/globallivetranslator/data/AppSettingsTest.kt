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
}
