package io.github.stwbeery.globallivetranslator.state

data class CaptionSnapshot(
    val source: String,
    val translated: String,
)

class SubtitleAccumulator(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sentenceGapMs: Long = DEFAULT_SENTENCE_GAP_MS,
    private val maxSentenceCodePoints: Int = DEFAULT_MAX_SENTENCE_CODE_POINTS,
) {
    private val source = CurrentSentence(maxSentenceCodePoints)
    private val translated = CurrentSentence(maxSentenceCodePoints)
    private var lastSourceFragmentAtMs: Long? = null
    private var lastTranslatedFragmentAtMs: Long? = null

    init {
        require(sentenceGapMs >= 0)
        require(maxSentenceCodePoints >= 2)
    }

    @Synchronized
    fun appendSource(fragment: String): CaptionSnapshot {
        if (fragment.isNotEmpty()) {
            val now = nowMs()
            if (gapElapsed(lastSourceFragmentAtMs, now)) source.startNextSentence()
            lastSourceFragmentAtMs = now
            source.append(fragment)
        }
        return snapshot()
    }

    @Synchronized
    fun appendTranslated(fragment: String): CaptionSnapshot {
        if (fragment.isNotEmpty()) {
            val now = nowMs()
            if (gapElapsed(lastTranslatedFragmentAtMs, now)) translated.startNextSentence()
            lastTranslatedFragmentAtMs = now
            translated.append(fragment)
        }
        return snapshot()
    }

    @Synchronized
    fun finalizeSentence(): CaptionSnapshot {
        source.startNextSentence()
        translated.startNextSentence()
        lastSourceFragmentAtMs = null
        lastTranslatedFragmentAtMs = null
        return snapshot()
    }

    @Synchronized
    fun clear(): CaptionSnapshot {
        source.clear()
        translated.clear()
        lastSourceFragmentAtMs = null
        lastTranslatedFragmentAtMs = null
        return snapshot()
    }

    private fun gapElapsed(lastFragmentAtMs: Long?, now: Long): Boolean =
        lastFragmentAtMs != null && now - lastFragmentAtMs >= sentenceGapMs

    private fun snapshot() = CaptionSnapshot(
        source = source.value(),
        translated = translated.value(),
    )

    private class CurrentSentence(private val maxCodePoints: Int) {
        private val text = StringBuilder()
        private var startNewOnNextContent = false

        fun append(fragment: String) {
            var offset = 0
            while (offset < fragment.length) {
                val codePoint = Character.codePointAt(fragment, offset)
                offset += Character.charCount(codePoint)

                if (startNewOnNextContent) {
                    when {
                        isSentenceTerminator(codePoint) || isTrailingCloser(codePoint) -> {
                            appendCodePoint(codePoint)
                            continue
                        }
                        Character.isWhitespace(codePoint) -> continue
                        continuesDecimal(codePoint) -> startNewOnNextContent = false
                        else -> clearForNextSentence()
                    }
                }

                if (Character.isWhitespace(codePoint) && text.isEmpty()) continue
                if (codePoint == '\n'.code || codePoint == '\r'.code) {
                    if (text.isNotEmpty()) startNewOnNextContent = true
                    continue
                }

                appendCodePoint(codePoint)
                if (isSentenceTerminator(codePoint)) startNewOnNextContent = true
            }
        }

        fun startNextSentence() {
            if (text.isNotEmpty()) startNewOnNextContent = true
        }

        fun clear() {
            text.setLength(0)
            startNewOnNextContent = false
        }

        fun value(): String = text.toString()

        private fun clearForNextSentence() {
            text.setLength(0)
            startNewOnNextContent = false
        }

        private fun appendCodePoint(codePoint: Int) {
            text.appendCodePoint(codePoint)
            trimToLimit()
        }

        private fun continuesDecimal(nextCodePoint: Int): Boolean =
            Character.isDigit(nextCodePoint) &&
                text.length >= 2 &&
                text[text.length - 1] == '.' &&
                Character.isDigit(text[text.length - 2])

        private fun trimToLimit() {
            val count = Character.codePointCount(text, 0, text.length)
            if (count <= maxCodePoints) return

            val retainedCount = maxCodePoints - 1
            val retainedStart = Character.offsetByCodePoints(text, text.length, -retainedCount)
            val retained = text.substring(retainedStart)
            text.setLength(0)
            text.append(ELLIPSIS)
            text.append(retained)
        }
    }

    internal companion object {
        const val DEFAULT_SENTENCE_GAP_MS = 2_000L
        const val DEFAULT_MAX_SENTENCE_CODE_POINTS = 24
        private const val ELLIPSIS = '\u2026'

        private fun isSentenceTerminator(codePoint: Int): Boolean = when (codePoint) {
            '.'.code,
            '!'.code,
            '?'.code,
            '\u3002'.code,
            '\uff01'.code,
            '\uff1f'.code,
            '\u2026'.code,
            '\uff61'.code,
            -> true
            else -> false
        }

        private fun isTrailingCloser(codePoint: Int): Boolean = when (codePoint) {
            '"'.code,
            '\''.code,
            ')'.code,
            ']'.code,
            '}'.code,
            '\u2019'.code,
            '\u201d'.code,
            '\u00bb'.code,
            '\u3009'.code,
            '\u300b'.code,
            '\u300d'.code,
            '\u300f'.code,
            '\u3011'.code,
            '\u3015'.code,
            '\u3017'.code,
            '\u3019'.code,
            '\u301b'.code,
            '\uff09'.code,
            -> true
            else -> false
        }
    }
}
