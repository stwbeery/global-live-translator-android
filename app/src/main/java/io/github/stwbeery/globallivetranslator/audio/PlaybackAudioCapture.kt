package io.github.stwbeery.globallivetranslator.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAudioCapture(
    private val projection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var captureJob: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        captureJob = scope.launch {
            try {
                val spec = createAudioRecord()
                record = spec.record
                val resampler = StreamingPcmResampler(spec.sampleRate, spec.channels)
                val assembler = PcmFrameAssembler()
                val input = ShortArray(spec.sampleRate * spec.channels / 10)
                spec.record.startRecording()
                while (isActive && running.get()) {
                    val read = spec.record.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                    when {
                        read > 0 -> assembler.append(resampler.process(input, read), onFrame)
                        read == AudioRecord.ERROR_DEAD_OBJECT -> error("系统音频采集设备已断开")
                        read < 0 -> error("读取系统音频失败：$read")
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) onError(error)
            } finally {
                releaseRecord()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { record?.stop() }
        captureJob?.cancel()
        releaseRecord()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): RecordSpec {
        val captureConfiguration = android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val candidates = listOf(
            48_000 to AudioFormat.CHANNEL_IN_STEREO,
            48_000 to AudioFormat.CHANNEL_IN_MONO,
            44_100 to AudioFormat.CHANNEL_IN_STEREO,
            44_100 to AudioFormat.CHANNEL_IN_MONO,
        )
        var lastError: Throwable? = null
        for ((sampleRate, channelMask) in candidates) {
            val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuffer <= 0) continue
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
                val candidate = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, sampleRate * channels / 2))
                    .setAudioPlaybackCaptureConfig(captureConfiguration)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return RecordSpec(candidate, sampleRate, channels)
                }
                candidate.release()
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "该设备无法初始化内部音频采集。目标 App 可能禁止录音，或系统不支持 AudioPlaybackCapture。",
            lastError,
        )
    }

    @Synchronized
    private fun releaseRecord() {
        val current = record ?: return
        record = null
        runCatching { current.stop() }
        current.release()
    }

    private data class RecordSpec(
        val record: AudioRecord,
        val sampleRate: Int,
        val channels: Int,
    )
}
