package io.github.stwbeery.globallivetranslator.state

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleAccumulatorTest {
    @Test
    fun fragmentsBuildOneCurrentSentence() {
        val accumulator = SubtitleAccumulator()
        accumulator.appendSource("Hello ")
        accumulator.appendSource("world")
        accumulator.appendTranslated("你好")
        assertEquals(CaptionSnapshot("Hello world", "你好"), accumulator.appendTranslated("，世界"))
    }

    @Test
    fun finalizedSentenceRemainsUntilNewTextArrives() {
        val accumulator = SubtitleAccumulator()
        accumulator.appendTranslated("第一句")
        assertEquals(CaptionSnapshot("", "第一句"), accumulator.finalizeSentence())
        assertEquals(CaptionSnapshot("", "第二"), accumulator.appendTranslated("第二"))
    }
}
