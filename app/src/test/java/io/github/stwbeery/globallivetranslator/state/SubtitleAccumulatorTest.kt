package io.github.stwbeery.globallivetranslator.state

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleAccumulatorTest {
    @Test
    fun fragmentsBuildOneCurrentSentence() {
        val accumulator = stableAccumulator()
        accumulator.appendSource("Hello ")
        accumulator.appendSource("world")
        accumulator.appendTranslated("你好")

        assertEquals(
            CaptionSnapshot("Hello world", "你好，世界"),
            accumulator.appendTranslated("，世界"),
        )
    }

    @Test
    fun completedSentenceRemainsUntilNextContentArrives() {
        val accumulator = stableAccumulator()

        assertEquals("第一句。", accumulator.appendTranslated("第一句。").translated)
        assertEquals("第一句。", accumulator.appendTranslated("  ").translated)
        assertEquals("第二", accumulator.appendTranslated("第二").translated)
    }

    @Test
    fun fragmentedTerminatorStartsANewSentenceOnFollowingContent() {
        val accumulator = stableAccumulator()

        accumulator.appendTranslated("第一句")
        assertEquals("第一句。", accumulator.appendTranslated("。").translated)
        assertEquals("第", accumulator.appendTranslated("第").translated)
        assertEquals("第二句", accumulator.appendTranslated("二句").translated)
    }

    @Test
    fun oneFragmentWithMultipleSentencesKeepsOnlyTheCurrentSentence() {
        val accumulator = stableAccumulator()

        assertEquals(
            "第三句正在继续",
            accumulator.appendTranslated("第一句。Second sentence! 第三句正在继续").translated,
        )
    }

    @Test
    fun trailingQuotesAndBracketsStayWithTheCompletedSentence() {
        val accumulator = stableAccumulator()

        assertEquals(
            "他说：“好了！",
            accumulator.appendTranslated("他说：“好了！").translated,
        )
        assertEquals("他说：“好了！”", accumulator.appendTranslated("”").translated)
        assertEquals("下一句", accumulator.appendTranslated(" 下一句").translated)

        accumulator.clear()
        assertEquals(
            "He said \"Done.\")",
            accumulator.appendTranslated("He said \"Done.\")").translated,
        )
        assertEquals("Next", accumulator.appendTranslated(" Next").translated)
    }

    @Test
    fun finalizeKeepsTheSentenceAndMakesTheNextFragmentReplaceIt() {
        val accumulator = stableAccumulator()
        accumulator.appendTranslated("第一句")

        assertEquals("第一句", accumulator.finalizeSentence().translated)
        assertEquals("第二句", accumulator.appendTranslated("第二句").translated)
    }

    @Test
    fun aTwoSecondGapStartsANewSentence() {
        var now = 1_000L
        val accumulator = SubtitleAccumulator(nowMs = { now })
        accumulator.appendTranslated("第一部分")

        now += SubtitleAccumulator.DEFAULT_SENTENCE_GAP_MS

        assertEquals("第二部分", accumulator.appendTranslated("第二部分").translated)
    }

    @Test
    fun shorterGapContinuesTheCurrentSentence() {
        var now = 1_000L
        val accumulator = SubtitleAccumulator(nowMs = { now })
        accumulator.appendTranslated("第一")

        now += SubtitleAccumulator.DEFAULT_SENTENCE_GAP_MS - 1

        assertEquals("第一第二", accumulator.appendTranslated("第二").translated)
    }

    @Test
    fun sourceFragmentsDoNotResetTheTranslatedGap() {
        var now = 1_000L
        val accumulator = SubtitleAccumulator(nowMs = { now })
        accumulator.appendTranslated("甲")

        repeat(5) {
            now += 500
            accumulator.appendSource("source")
        }

        assertEquals("乙", accumulator.appendTranslated("乙").translated)
    }

    @Test
    fun translatedFragmentsDoNotResetTheSourceGap() {
        var now = 1_000L
        val accumulator = SubtitleAccumulator(nowMs = { now })
        accumulator.appendSource("A")

        repeat(5) {
            now += 500
            accumulator.appendTranslated("译")
        }

        assertEquals("B", accumulator.appendSource("B").source)
    }

    @Test
    fun decimalPointDoesNotSplitTheCurrentSentence() {
        val accumulator = stableAccumulator()

        accumulator.appendTranslated("价格是 3.")

        assertEquals("价格是 3.5 美元。", accumulator.appendTranslated("5 美元。").translated)
    }

    @Test
    fun defaultWindowKeepsALiveTailBeforeFourLinesCanFreeze() {
        val accumulator = stableAccumulator()
        val latest = accumulator.appendTranslated("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十").translated

        assertEquals(SubtitleAccumulator.DEFAULT_MAX_SENTENCE_CODE_POINTS, latest.codePointCount(0, latest.length))
        assertEquals("…八九十一二三四五六七八九十一二三四五六七八九十", latest)
    }

    @Test
    fun longUnpunctuatedTextKeepsTheLatestUnicodeCodePointTail() {
        val accumulator = stableAccumulator(maxSentenceCodePoints = 6)

        assertEquals("…DEFG😀", accumulator.appendTranslated("ABCDEFG😀").translated)
        val latest = accumulator.appendTranslated("H").translated
        assertEquals("…EFG😀H", latest)
        assertEquals(6, latest.codePointCount(0, latest.length))
    }

    @Test
    fun clearRemovesBothSidesAndSentenceBoundaries() {
        val accumulator = stableAccumulator()
        accumulator.appendSource("Source.")
        accumulator.appendTranslated("译文。")

        assertEquals(CaptionSnapshot("", ""), accumulator.clear())
        assertEquals("New", accumulator.appendTranslated("New").translated)
    }

    private fun stableAccumulator(
        maxSentenceCodePoints: Int = SubtitleAccumulator.DEFAULT_MAX_SENTENCE_CODE_POINTS,
    ) = SubtitleAccumulator(
        nowMs = { 0L },
        maxSentenceCodePoints = maxSentenceCodePoints,
    )
}
