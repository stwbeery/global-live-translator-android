package io.github.stwbeery.globallivetranslator.audio

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.sqrt

class StreamingPcmResampler(
    private val inputSampleRate: Int,
    private val inputChannels: Int,
    private val outputSampleRate: Int = 16_000,
) {
    private val step = inputSampleRate.toDouble() / outputSampleRate
    private var pending = FloatArray(0)
    private var sourcePosition = 0.0

    init {
        require(inputSampleRate > 0)
        require(inputChannels > 0)
        require(outputSampleRate > 0)
    }

    fun process(input: ShortArray, length: Int = input.size): ShortArray {
        require(length in 0..input.size)
        val frameCount = length / inputChannels
        if (frameCount == 0) return ShortArray(0)

        val combined = FloatArray(pending.size + frameCount)
        pending.copyInto(combined)
        for (frame in 0 until frameCount) {
            var sum = 0
            val offset = frame * inputChannels
            for (channel in 0 until inputChannels) sum += input[offset + channel].toInt()
            combined[pending.size + frame] = sum.toFloat() / inputChannels
        }

        val capacity = ceil((combined.size - sourcePosition).coerceAtLeast(0.0) / step).toInt()
        val output = ShortArray(capacity)
        var outputSize = 0
        while (sourcePosition + 1 < combined.size) {
            val index = sourcePosition.toInt()
            val fraction = sourcePosition - index
            val sample = combined[index] * (1.0 - fraction) + combined[index + 1] * fraction
            output[outputSize++] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            sourcePosition += step
        }

        val consumed = sourcePosition.toInt().coerceAtMost(combined.size)
        pending = combined.copyOfRange(consumed, combined.size)
        sourcePosition -= consumed
        return output.copyOf(outputSize)
    }

    fun reset() {
        pending = FloatArray(0)
        sourcePosition = 0.0
    }
}

class PcmFrameAssembler(private val samplesPerFrame: Int = 1_600) {
    private val frame = ShortArray(samplesPerFrame)
    private var frameSize = 0

    fun append(samples: ShortArray, onFrame: (ByteArray) -> Unit) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(samplesPerFrame - frameSize, samples.size - offset)
            samples.copyInto(frame, frameSize, offset, offset + count)
            frameSize += count
            offset += count
            if (frameSize == samplesPerFrame) {
                onFrame(frame.toPcm16Le())
                frameSize = 0
            }
        }
    }

    fun reset() {
        frameSize = 0
    }
}

sealed interface VadEvent {
    data class Audio(val pcm: ByteArray) : VadEvent
    data object StreamEnd : VadEvent
}

class VadGate(
    private val thresholdDb: Float = -50f,
    private val openFrames: Int = 2,
    private val preRollFrames: Int = 5,
    private val hangoverFrames: Int = 20,
) {
    private val preRoll = ArrayDeque<ByteArray>()
    private var voicedFrames = 0
    private var silentFrames = 0
    private var open = false

    fun process(frame: ByteArray): List<VadEvent> {
        val voiced = dbFs(frame) >= thresholdDb
        if (open) {
            val events = mutableListOf<VadEvent>(VadEvent.Audio(frame))
            silentFrames = if (voiced) 0 else silentFrames + 1
            if (silentFrames >= hangoverFrames) {
                events += VadEvent.StreamEnd
                open = false
                voicedFrames = 0
                silentFrames = 0
                preRoll.clear()
            }
            return events
        }

        preRoll.addLast(frame)
        while (preRoll.size > preRollFrames) preRoll.removeFirst()
        voicedFrames = if (voiced) voicedFrames + 1 else 0
        if (voicedFrames < openFrames) return emptyList()

        open = true
        silentFrames = 0
        return buildList {
            while (preRoll.isNotEmpty()) add(VadEvent.Audio(preRoll.removeFirst()))
        }
    }

    fun reset() {
        preRoll.clear()
        voicedFrames = 0
        silentFrames = 0
        open = false
    }

    private fun dbFs(frame: ByteArray): Float {
        if (frame.size < 2) return -120f
        var sumSquares = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < frame.size) {
            val sample = ((frame[index].toInt() and 0xff) or (frame[index + 1].toInt() shl 8)).toShort().toInt()
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            samples++
            index += 2
        }
        if (sumSquares == 0.0 || samples == 0) return -120f
        return (20.0 * log10(sqrt(sumSquares / samples))).toFloat()
    }
}

private fun ShortArray.toPcm16Le(): ByteArray {
    val bytes = ByteArray(size * 2)
    forEachIndexed { index, sample ->
        val value = sample.toInt()
        bytes[index * 2] = (value and 0xff).toByte()
        bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
    }
    return bytes
}
