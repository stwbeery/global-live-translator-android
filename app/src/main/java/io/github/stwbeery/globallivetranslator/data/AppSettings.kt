package io.github.stwbeery.globallivetranslator.data

enum class ProxyMode {
    SYSTEM,
    HTTP,
    SOCKS,
}

data class AppSettings(
    val apiKey: String = "",
    val targetLanguage: String = "zh-Hans",
    val proxyMode: ProxyMode = ProxyMode.SYSTEM,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 7890,
    val overlayEnabled: Boolean = true,
    val vadThresholdDb: Float = -50f,
) {
    fun validate(): String? {
        if (apiKey.isBlank()) return "请输入 Gemini API Key"
        if (targetLanguage.isBlank()) return "请输入目标语言代码"
        if (proxyMode != ProxyMode.SYSTEM) {
            if (proxyHost.isBlank()) return "请输入代理地址"
            if (proxyPort !in 1..65535) return "代理端口必须在 1-65535 之间"
        }
        if (vadThresholdDb !in -80f..-10f) return "语音阈值必须在 -80 到 -10 dB 之间"
        return null
    }
}
