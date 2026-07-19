package io.github.stwbeery.globallivetranslator.network

import io.github.stwbeery.globallivetranslator.data.AppSettings
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProtocolTest {
    @Test
    fun binaryServerMessagesUseTheJsonMessagePath() {
        val listener = RecordingListener()
        val session = GeminiLiveSession(AppSettings(), listener)
        val webSocket = FakeWebSocket()

        try {
            session.start(connectImmediately = false)
            val socketListener = session.createListener(0)
            socketListener.onMessage(webSocket, """{"setupComplete":{}}""".encodeUtf8())
            socketListener.onMessage(
                webSocket,
                """{"serverContent":{"outputTranscription":{"text":"你好"},"turnComplete":true}}"""
                    .encodeUtf8(),
            )

            assertEquals(
                listOf(
                    GeminiSessionStatus.WAITING_FOR_AUDIO,
                    GeminiSessionStatus.WAITING_FOR_AUDIO,
                ),
                listener.statuses,
            )
            assertEquals(
                listOf("等待手机声音", "Gemini 已连接，等待手机声音"),
                listener.details,
            )
            assertEquals(listOf("你好"), listener.translations)
        } finally {
            session.stop()
        }
    }

    @Test
    fun firstAudioAfterSetupTransitionsToTranslating() {
        val listener = RecordingListener()
        val session = GeminiLiveSession(AppSettings(), listener)
        val webSocket = FakeWebSocket()

        try {
            session.start(connectImmediately = false)
            session.createListener(0)
                .onMessage(webSocket, """{"setupComplete":{}}""".encodeUtf8())

            session.sendAudio(byteArrayOf(1, 2, 3, 4))

            assertEquals(GeminiSessionStatus.TRANSLATING, listener.statuses.last())
            assertEquals("正在实时翻译", listener.details.last())
        } finally {
            session.stop()
        }
    }

    @Test
    fun setupMatchesLiveTranslateProtocol() {
        val setup = JSONObject(GeminiLiveSession.setupMessage("zh-Hans", "resume-me")).getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        assertEquals(
            "zh-Hans",
            setup.getJSONObject("generationConfig")
                .getJSONObject("translationConfig")
                .getString("targetLanguageCode"),
        )
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        assertEquals("resume-me", setup.getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun durationParserAcceptsSecondsAndNumbers() {
        assertEquals(1_500L, GeminiLiveSession.parseDurationMs("1.5s"))
        assertEquals(250L, GeminiLiveSession.parseDurationMs(250))
        assertEquals(0L, GeminiLiveSession.parseDurationMs("bad"))
    }

    @Test
    fun errorsRedactQueryKey() {
        val sanitized = GeminiLiveSession.sanitizeError("failed wss://host/path?key=super-secret&x=1")
        assertFalse(sanitized.contains("super-secret"))
        assertTrue(sanitized.contains("[redacted]"))
    }

    @Test
    fun policyAndApplicationCloseCodesAreTerminal() {
        assertTrue(GeminiLiveSession.isTerminalCloseCode(1008))
        assertTrue(GeminiLiveSession.isTerminalCloseCode(4003))
        assertFalse(GeminiLiveSession.isTerminalCloseCode(1011))
    }

    private class RecordingListener : GeminiSessionListener {
        val statuses = mutableListOf<GeminiSessionStatus>()
        val details = mutableListOf<String>()
        val translations = mutableListOf<String>()

        override fun onStatus(status: GeminiSessionStatus, detail: String) {
            statuses += status
            details += detail
        }

        override fun onTranscript(
            sourceFragment: String?,
            translatedFragment: String?,
            final: Boolean,
        ) {
            translatedFragment?.let(translations::add)
        }

        override fun onAudioSent(bytes: Int) = Unit

        override fun onError(message: String) = Unit
    }

    private class FakeWebSocket : WebSocket {
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = true

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() = Unit
    }
}
