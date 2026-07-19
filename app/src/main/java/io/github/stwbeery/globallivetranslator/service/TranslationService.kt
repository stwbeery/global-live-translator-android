package io.github.stwbeery.globallivetranslator.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.stwbeery.globallivetranslator.MainActivity
import io.github.stwbeery.globallivetranslator.R
import io.github.stwbeery.globallivetranslator.audio.PlaybackAudioCapture
import io.github.stwbeery.globallivetranslator.audio.VadEvent
import io.github.stwbeery.globallivetranslator.audio.VadGate
import io.github.stwbeery.globallivetranslator.data.SecureSettingsStore
import io.github.stwbeery.globallivetranslator.network.GeminiLiveSession
import io.github.stwbeery.globallivetranslator.network.GeminiSessionListener
import io.github.stwbeery.globallivetranslator.network.GeminiSessionStatus
import io.github.stwbeery.globallivetranslator.overlay.CaptionOverlay
import io.github.stwbeery.globallivetranslator.state.SubtitleAccumulator
import io.github.stwbeery.globallivetranslator.state.TranslationPhase
import io.github.stwbeery.globallivetranslator.state.TranslationStateStore
import java.util.concurrent.atomic.AtomicBoolean

class TranslationService : Service(), GeminiSessionListener {
    private val stopping = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: SecureSettingsStore
    private lateinit var overlay: CaptionOverlay
    private val subtitles = SubtitleAccumulator()
    private var mediaProjection: MediaProjection? = null
    private var capture: PlaybackAudioCapture? = null
    private var session: GeminiLiveSession? = null
    private var vad: VadGate? = null
    private var overlayEnabled = false
    private var hasTranslatedCaption = false
    @Volatile
    private var hasTerminalError = false

    override fun onCreate() {
        super.onCreate()
        settingsStore = SecureSettingsStore(this)
        overlay = CaptionOverlay(this, settingsStore::saveOverlayPosition)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTranslation(manual = true)
            ACTION_START -> startTranslation(intent)
            ACTION_UPDATE_OVERLAY -> {
                if (capture != null && session != null) {
                    updateOverlayAppearance()
                } else {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val wasStopping = stopping.getAndSet(true)
        releaseResources()
        if (!hasTerminalError && !wasStopping) TranslationStateStore.reset()
        super.onDestroy()
    }

    override fun onStatus(status: GeminiSessionStatus, detail: String) {
        mainHandler.post {
            if (stopping.get() || hasTerminalError) return@post
            val phase = when (status) {
                GeminiSessionStatus.WAITING_FOR_AUDIO -> TranslationPhase.WAITING_FOR_AUDIO
                GeminiSessionStatus.CONNECTING -> TranslationPhase.CONNECTING
                GeminiSessionStatus.TRANSLATING -> TranslationPhase.TRANSLATING
                GeminiSessionStatus.RECONNECTING -> TranslationPhase.RECONNECTING
                GeminiSessionStatus.STOPPED -> TranslationPhase.STOPPING
            }
            TranslationStateStore.setPhase(phase, detail)
            updateNotification(detail)
            if (overlayEnabled && !hasTranslatedCaption) overlay.show(detail)
        }
    }

    override fun onTranscript(sourceFragment: String?, translatedFragment: String?, final: Boolean) {
        mainHandler.post {
            if (stopping.get() || hasTerminalError) return@post
            var snapshot = sourceFragment?.let(subtitles::appendSource)
            translatedFragment?.let { snapshot = subtitles.appendTranslated(it) }
            if (final) snapshot = subtitles.finalizeSentence()
            snapshot?.let { updated ->
                TranslationStateStore.setCaption(updated.source, updated.translated)
                if (overlayEnabled && translatedFragment != null && updated.translated.isNotBlank()) {
                    hasTranslatedCaption = true
                    overlay.show(updated.translated)
                }
            }
        }
    }

    override fun onAudioSent(bytes: Int) {
        if (stopping.get() || hasTerminalError) return
        TranslationStateStore.addBytesSent(bytes)
    }

    override fun onError(message: String) {
        mainHandler.post { fail(message) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayEnabled && !stopping.get()) updateOverlayAppearance()
    }

    private fun startTranslation(intent: Intent) {
        if (capture != null || session != null) return
        stopping.set(false)
        hasTerminalError = false
        startForegroundNotification("正在准备内部音频采集")

        val settings = settingsStore.load()
        settings.validate()?.let {
            fail(it)
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = projectionIntent(intent)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            fail("缺少系统录屏授权，请返回应用重新启动")
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { manager.getMediaProjection(resultCode, resultData) }
            .getOrElse {
                fail("无法取得系统音频捕获权限：${it.message ?: "未知错误"}")
                return
            }
        mediaProjection = projection
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping.get()) fail("系统已停止内部音频捕获")
                }
            },
            mainHandler,
        )

        overlayEnabled = settings.overlayEnabled
        hasTranslatedCaption = false
        subtitles.clear()
        if (overlayEnabled) {
            overlay.attach(settings, settingsStore.loadOverlayPosition(), "正在连接 Gemini")
        }
        val liveSession = GeminiLiveSession(settings, this)
        session = liveSession
        vad = VadGate(thresholdDb = settings.vadThresholdDb)
        val playbackCapture = PlaybackAudioCapture(
            projection = projection,
            onFrame = { frame ->
                vad?.process(frame)?.forEach { event ->
                    when (event) {
                        is VadEvent.Audio -> liveSession.sendAudio(event.pcm)
                        VadEvent.StreamEnd -> liveSession.sendAudioStreamEnd()
                    }
                }
            },
            onError = { error ->
                mainHandler.post { fail(error.message ?: "内部音频采集失败") }
            },
        )
        capture = playbackCapture
        TranslationStateStore.setPhase(TranslationPhase.WAITING_FOR_AUDIO, "等待手机声音")
        updateNotification("等待手机声音")
        liveSession.start()
        playbackCapture.start()
    }

    private fun stopTranslation(manual: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        hasTerminalError = !manual
        if (manual) TranslationStateStore.setPhase(TranslationPhase.STOPPING, "正在停止")
        releaseResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (manual) TranslationStateStore.reset()
        stopSelf()
    }

    private fun fail(message: String) {
        if (stopping.get()) return
        hasTerminalError = true
        TranslationStateStore.setPhase(TranslationPhase.ERROR, "已停止", message)
        updateNotification(message)
        mainHandler.post { stopTranslation(manual = false) }
    }

    private fun releaseResources() {
        capture?.stop()
        capture = null
        session?.stop()
        session = null
        vad?.reset()
        vad = null
        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.stop() }
        overlay.remove()
        hasTranslatedCaption = false
    }

    private fun updateOverlayAppearance() {
        if (capture == null || session == null) return
        val settings = settingsStore.load()
        overlayEnabled = settings.overlayEnabled
        if (overlayEnabled) {
            val state = TranslationStateStore.state.value
            val currentText = state.translatedText.takeIf { it.isNotBlank() } ?: state.status
            overlay.attach(settings, settingsStore.loadOverlayPosition(), currentText)
        } else {
            overlay.remove()
        }
    }

    private fun startForegroundNotification(status: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(status),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
    }

    private fun updateNotification(status: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status.take(100)))
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle("全局实时同传")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "实时同传",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "内部音频捕获与实时翻译状态"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_RESULT_DATA)
    }

    companion object {
        private const val ACTION_START = "io.github.stwbeery.globallivetranslator.START"
        private const val ACTION_STOP = "io.github.stwbeery.globallivetranslator.STOP"
        private const val ACTION_UPDATE_OVERLAY = "io.github.stwbeery.globallivetranslator.UPDATE_OVERLAY"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_CHANNEL_ID = "live_translation"
        private const val NOTIFICATION_ID = 2107

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, TranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TranslationService::class.java).setAction(ACTION_STOP),
            )
        }

        fun updateOverlay(context: Context) {
            context.startService(
                Intent(context, TranslationService::class.java).setAction(ACTION_UPDATE_OVERLAY),
            )
        }
    }
}
