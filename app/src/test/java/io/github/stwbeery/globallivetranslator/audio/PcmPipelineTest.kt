package io.github.stwbeery.globallivetranslator.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PcmPipelineTest {
    @Test
    fun stereo48kDownmixesAndResamplesTo16k() {
        val input = ShortArray(4_800 * 2) { index ->
            val frame = index / 2
            (sin(2 * PI * 440 * frame / 48_000) * 12_000).toInt().toShort()
        }
        val output = StreamingPcmResampler(48_000, 2).process(input)
        assertTrue(output.size in 1_599..1_601)
        assertTrue(output.any { it.toInt() != 0 })
    }

    @Test
    fun assemblerEmits100msPcm16LittleEndianFrames() {
        val emitted = mutableListOf<ByteArray>()
        val assembler = PcmFrameAssembler(samplesPerFrame = 4)
        assembler.append(shortArrayOf(1, -2, 3)) { emitted += it }
        assembler.append(shortArrayOf(4, 5, 6, 7, 8)) { emitted += it }
        assertEquals(2, emitted.size)
        assertEquals(listOf(1, 0, 254, 255, 3, 0, 4, 0), emitted[0].map { it.toInt() and 0xff })
    }

    @Test
    fun vadAddsPrerollThenClosesAfterHangover() {
        val gate = VadGate(thresholdDb = -40f, openFrames = 2, preRollFrames = 3, hangoverFrames = 2)
        val silence = ByteArray(8)
        val voice = byteArrayOf(0, 64, 0, 64, 0, 64, 0, 64)
        gate.process(silence)
        assertTrue(gate.process(voice).isEmpty())
        val opened = gate.process(voice)
        assertEquals(3, opened.filterIsInstance<VadEvent.Audio>().size)
        gate.process(silence)
        assertTrue(gate.process(silence).contains(VadEvent.StreamEnd))
    }

    @Test
    fun dbFsReportsSilenceAndKnownAmplitude() {
        assertEquals(-120f, VadGate.dbFs(ByteArray(3)))
        val halfScale = byteArrayOf(0, 64, 0, 64, 0, 64, 0, 64)
        assertEquals(-6.02f, VadGate.dbFs(halfScale), 0.02f)
        assertEquals(-6.02f, pcm16DbFs(shortArrayOf(16_384, 16_384)), 0.02f)
    }
}
