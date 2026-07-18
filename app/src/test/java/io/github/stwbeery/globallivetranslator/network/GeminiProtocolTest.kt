package io.github.stwbeery.globallivetranslator.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProtocolTest {
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
}
