package io.github.stwbeery.globallivetranslator.state

data class CaptionSnapshot(
    val source: String,
    val translated: String,
)

class SubtitleAccumulator {
    private var currentSource = StringBuilder()
    private var currentTranslated = StringBuilder()
    private var lastSource = ""
    private var lastTranslated = ""

    @Synchronized
    fun appendSource(fragment: String): CaptionSnapshot {
        if (fragment.isNotEmpty()) currentSource.append(fragment)
        return snapshot()
    }

    @Synchronized
    fun appendTranslated(fragment: String): CaptionSnapshot {
        if (fragment.isNotEmpty()) currentTranslated.append(fragment)
        return snapshot()
    }

    @Synchronized
    fun finalizeSentence(): CaptionSnapshot {
        currentSource.toString().trim().takeIf { it.isNotEmpty() }?.let { lastSource = it }
        currentTranslated.toString().trim().takeIf { it.isNotEmpty() }?.let { lastTranslated = it }
        currentSource = StringBuilder()
        currentTranslated = StringBuilder()
        return CaptionSnapshot(lastSource, lastTranslated)
    }

    @Synchronized
    fun clear(): CaptionSnapshot {
        currentSource = StringBuilder()
        currentTranslated = StringBuilder()
        lastSource = ""
        lastTranslated = ""
        return CaptionSnapshot("", "")
    }

    private fun snapshot() = CaptionSnapshot(
        source = currentSource.toString().ifBlank { lastSource },
        translated = currentTranslated.toString().ifBlank { lastTranslated },
    )
}
