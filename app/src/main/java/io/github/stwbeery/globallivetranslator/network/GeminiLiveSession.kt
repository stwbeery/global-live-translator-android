package io.github.stwbeery.globallivetranslator.network

import io.github.stwbeery.globallivetranslator.data.AppSettings
import io.github.stwbeery.globallivetranslator.data.ProxyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface GeminiSessionListener {
    fun onStatus(status: GeminiSessionStatus, detail: String)
    fun onTranscript(sourceFragment: String?, translatedFragment: String?, final: Boolean)
    fun onAudioSent(bytes: Int)
    fun onDiagnostics(message: String) = Unit
    fun onError(message: String)
}

internal enum class GeminiMessageKind {
    SETUP_COMPLETE,
    SERVER_ERROR,
    SERVER_CONTENT,
    SESSION_RESUMPTION,
    GO_AWAY,
    OTHER,
}

enum class GeminiSessionStatus {
    WAITING_FOR_AUDIO,
    CONNECTING,
    TRANSLATING,
    RECONNECTING,
    STOPPED,
}

class GeminiLiveSession(
    private val settings: AppSettings,
    private val listener: GeminiSessionListener,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = buildClient(settings)
    private val queue = ArrayDeque<ByteArray>()
    private var started = false
    private var terminal = false
    private var connecting = false
    private var ready = false
    private var audioActive = false
    private var generation = 0L
    private var reconnectAttempt = 0
    private var resumptionHandle: String? = null
    private var socket: WebSocket? = null
    private var pendingStreamEnd = false
    private var reconnectJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var idleCloseJob: Job? = null
    private var idleCloseToken = 0L
    private var goAwayJob: Job? = null
    private val receivedMessages = AtomicLong()
    private val receivedServerContents = AtomicLong()
    private val receivedSourceTranscriptions = AtomicLong()
    private val receivedOutputTranscriptions = AtomicLong()
    private val receivedModelTexts = AtomicLong()
    private val receivedModelTurns = AtomicLong()
    private val invalidMessages = AtomicLong()
    private val sentAudioFrames = AtomicLong()
    private val sentAudioBytes = AtomicLong()
    private val rejectedAudioFrames = AtomicLong()
    private val droppedQueuedFrames = AtomicLong()
    private val lastDiagnosticsAtNs = AtomicLong()

    fun start(connectImmediately: Boolean = true) {
        synchronized(lock) {
            if (started) return
            started = true
            terminal = false
        }
        if (connectImmediately) {
            connect(isReconnect = false)
        } else {
            listener.onStatus(GeminiSessionStatus.WAITING_FOR_AUDIO, "等待手机声音")
        }
    }

    fun sendAudio(pcm: ByteArray) {
        var directSocket: WebSocket? = null
        var shouldConnect = false
        var becameActive = false
        synchronized(lock) {
            if (!started || terminal) return
            idleCloseJob?.cancel()
            idleCloseJob = null
            idleCloseToken++
            if (ready) {
                directSocket = socket
                if (!audioActive) {
                    audioActive = true
                    becameActive = true
                }
            } else {
                queue.addLast(pcm.copyOf())
                while (queue.size > MAX_QUEUED_FRAMES) {
                    queue.removeFirst()
                    droppedQueuedFrames.incrementAndGet()
                }
                shouldConnect = !connecting && socket == null && reconnectJob == null
            }
        }
        if (directSocket != null) {
            if (becameActive) {
                listener.onStatus(GeminiSessionStatus.TRANSLATING, "正在实时翻译")
            }
            sendFrame(directSocket!!, pcm)
        } else if (shouldConnect) {
            connect(isReconnect = false)
        }
    }

    fun sendAudioStreamEnd() {
        val currentSocket: WebSocket?
        synchronized(lock) {
            if (!started || terminal) return
            if (!ready) {
                pendingStreamEnd = true
                return
            }
            audioActive = false
            currentSocket = socket
        }
        currentSocket?.send(audioStreamEndMessage())
        scheduleIdleClose()
    }

    fun stop() {
        val currentSocket: WebSocket?
        synchronized(lock) {
            if (!started) return
            started = false
            terminal = false
            generation++
            connecting = false
            ready = false
            audioActive = false
            currentSocket = socket
            socket = null
            queue.clear()
            pendingStreamEnd = false
            cancelTimersLocked()
        }
        currentSocket?.close(1000, "user stop")
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        scope.cancel()
        emitNetworkDiagnostics(force = true)
        listener.onStatus(GeminiSessionStatus.STOPPED, "已停止")
    }

    private fun connect(isReconnect: Boolean) {
        val connectionGeneration: Long
        val request: Request
        synchronized(lock) {
            if (!started || terminal || connecting || socket != null) return
            connecting = true
            ready = false
            generation++
            connectionGeneration = generation
            request = Request.Builder().url(webSocketUrl(settings.apiKey)).build()
        }
        listener.onStatus(
            if (isReconnect) GeminiSessionStatus.RECONNECTING else GeminiSessionStatus.CONNECTING,
            if (isReconnect) "正在重新连接" else "正在连接 Gemini",
        )
        diagnostic("Gemini connect generation=$connectionGeneration reconnect=$isReconnect")
        val newSocket = client.newWebSocket(request, createListener(connectionGeneration))
        synchronized(lock) {
            if (started && generation == connectionGeneration && (connecting || ready)) {
                if (socket == null) socket = newSocket
            } else {
                newSocket.cancel()
            }
        }
    }

    internal fun createListener(connectionGeneration: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val adopted = synchronized(lock) {
                if (!started || generation != connectionGeneration) {
                    false
                } else {
                    if (socket == null) socket = webSocket
                    socket === webSocket
                }
            }
            if (!adopted) {
                webSocket.close(1000, "stale")
                return
            }
            val setupAccepted = webSocket.send(setupMessage(settings.targetLanguage, resumptionHandle))
            diagnostic(
                "Gemini open generation=$connectionGeneration http=${response.code} setupAccepted=$setupAccepted",
            )
            if (!setupAccepted) {
                webSocket.cancel()
                handleDisconnect(connectionGeneration, "Gemini 初始化消息发送失败")
                return
            }
            val timeoutJob = scope.launch {
                delay(SETUP_TIMEOUT_MS)
                val timedOut = synchronized(lock) {
                    started && generation == connectionGeneration && !ready
                }
                if (timedOut) rotateConnection(connectionGeneration, "Gemini 初始化超时")
            }
            synchronized(lock) {
                if (started && generation == connectionGeneration && !ready) {
                    setupTimeoutJob?.cancel()
                    setupTimeoutJob = timeoutJob
                } else {
                    timeoutJob.cancel()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncomingJson(connectionGeneration, webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleIncomingJson(connectionGeneration, webSocket, bytes.utf8())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            diagnostic("Gemini closed generation=$connectionGeneration code=$code")
            val normalizedReason = sanitizeError(reason)
            val retryWithoutResumption = synchronized(lock) {
                val mentionsResumption = normalizedReason.contains("resum", ignoreCase = true) ||
                    normalizedReason.contains("handle", ignoreCase = true)
                if (started && generation == connectionGeneration &&
                    resumptionHandle != null && mentionsResumption
                ) {
                    resumptionHandle = null
                    true
                } else {
                    false
                }
            }
            if (retryWithoutResumption) {
                handleDisconnect(connectionGeneration, "会话恢复已失效，正在建立新会话")
            } else if (isTerminalCloseCode(code)) {
                handleTerminal(connectionGeneration, "Gemini 拒绝了会话（$code）${normalizedReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            } else {
                handleDisconnect(connectionGeneration, "连接已关闭（$code）")
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            val code = response?.code
            diagnostic("Gemini failure generation=$connectionGeneration http=${code ?: 0}")
            if (code != null && code in 400..499 && code !in setOf(408, 425, 429)) {
                val retryWithoutResumption = synchronized(lock) {
                    if (started && generation == connectionGeneration && code == 400 && resumptionHandle != null) {
                        resumptionHandle = null
                        true
                    } else {
                        false
                    }
                }
                if (retryWithoutResumption) {
                    handleDisconnect(connectionGeneration, "会话恢复已失效，正在建立新会话")
                } else {
                    handleTerminal(connectionGeneration, "Gemini 连接被拒绝（HTTP $code），请检查 API Key、模型权限和网络")
                }
            } else {
                handleDisconnect(connectionGeneration, sanitizeError(error.message ?: "网络连接失败"))
            }
        }
    }

    private fun handleIncomingJson(
        connectionGeneration: Long,
        webSocket: WebSocket,
        payload: String,
    ) {
        if (!isCurrent(connectionGeneration)) return
        val message = runCatching { JSONObject(payload) }.getOrNull()
        if (message == null) {
            invalidMessages.incrementAndGet()
            emitNetworkDiagnostics()
            return
        }
        receivedMessages.incrementAndGet()
        val messageKind = classifyMessage(message)
        message.optJSONObject("error")?.let { error ->
            val reason = serverErrorMessage(error)
            diagnostic("Gemini message kind=$messageKind")
            emitNetworkDiagnostics(force = true)
            handleTerminal(connectionGeneration, reason)
            return
        }
        if (message.has("setupComplete")) {
            diagnostic("Gemini message kind=$messageKind")
            handleSetupComplete(connectionGeneration, webSocket)
            return
        }
        handleServerMessage(connectionGeneration, message)
    }

    private fun handleSetupComplete(connectionGeneration: Long, webSocket: WebSocket) {
        val sendEnd: Boolean
        val hadQueuedAudio: Boolean
        val queuedAudioFrames: Int
        synchronized(lock) {
            if (!started || generation != connectionGeneration) return
            socket = webSocket
            setupTimeoutJob?.cancel()
            setupTimeoutJob = null
            connecting = false
            ready = true
            reconnectAttempt = 0
            hadQueuedAudio = queue.isNotEmpty()
            queuedAudioFrames = queue.size
            while (queue.isNotEmpty()) sendFrame(webSocket, queue.removeFirst())
            sendEnd = pendingStreamEnd
            pendingStreamEnd = false
            audioActive = hadQueuedAudio && !sendEnd
            if (sendEnd) webSocket.send(audioStreamEndMessage())
            listener.onStatus(
                if (hadQueuedAudio) GeminiSessionStatus.TRANSLATING else GeminiSessionStatus.WAITING_FOR_AUDIO,
                if (hadQueuedAudio) "正在实时翻译" else "Gemini 已连接，等待手机声音",
            )
        }
        diagnostic(
            "Gemini setupComplete generation=$connectionGeneration queuedAudioFrames=$queuedAudioFrames " +
                "pendingStreamEnd=$sendEnd",
        )
        if (sendEnd || !hadQueuedAudio) scheduleIdleClose()
    }

    private fun handleServerMessage(connectionGeneration: Long, message: JSONObject) {
        val serverContent = message.optJSONObject("serverContent")
        if (serverContent != null) {
            receivedServerContents.incrementAndGet()
            val source = serverContent.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() }
            val outputTranscription = serverContent.optJSONObject("outputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() }
            val modelText = extractModelText(serverContent)
            val translated = outputTranscription ?: modelText
            val final = serverContent.optBoolean("turnComplete") ||
                serverContent.optBoolean("generationComplete")
            if (source != null) receivedSourceTranscriptions.incrementAndGet()
            if (outputTranscription != null) receivedOutputTranscriptions.incrementAndGet()
            if (modelText != null) receivedModelTexts.incrementAndGet()
            if (serverContent.optJSONObject("modelTurn") != null) receivedModelTurns.incrementAndGet()
            emitNetworkDiagnostics()
            if (source != null || translated != null || final) {
                listener.onTranscript(source, translated, final)
            }
        }

        message.optJSONObject("sessionResumptionUpdate")?.let { update ->
            if (update.optBoolean("resumable")) {
                update.optString("newHandle").takeIf { it.isNotBlank() }?.let { handle ->
                    synchronized(lock) {
                        if (generation == connectionGeneration) resumptionHandle = handle
                    }
                }
            }
        }

        message.optJSONObject("goAway")?.let { goAway ->
            val delayMs = parseDurationMs(goAway.opt("timeLeft"))
            scheduleRotation(connectionGeneration, (delayMs - GO_AWAY_MARGIN_MS).coerceAtLeast(0))
        }
    }

    private fun handleDisconnect(connectionGeneration: Long, reason: String) {
        val retryDelay: Long
        synchronized(lock) {
            if (!started || generation != connectionGeneration) return
            setupTimeoutJob?.cancel()
            setupTimeoutJob = null
            socket = null
            connecting = false
            ready = false
            audioActive = false
            retryDelay = RECONNECT_DELAYS_MS[reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
            if (reconnectJob != null) return
            reconnectJob = scope.launch {
                listener.onStatus(GeminiSessionStatus.RECONNECTING, "$reason，${retryDelay / 1000} 秒后重试")
                delay(retryDelay)
                val reconnect = synchronized(lock) {
                    val active = started && generation == connectionGeneration
                    reconnectJob = null
                    active
                }
                if (reconnect) connect(isReconnect = true)
            }
        }
    }

    private fun handleTerminal(connectionGeneration: Long, reason: String) {
        val currentSocket: WebSocket?
        synchronized(lock) {
            if (!started || generation != connectionGeneration) return
            generation++
            terminal = true
            currentSocket = socket
            socket = null
            connecting = false
            ready = false
            audioActive = false
            queue.clear()
            pendingStreamEnd = false
            cancelTimersLocked()
        }
        currentSocket?.cancel()
        listener.onError(sanitizeError(reason))
    }

    private fun rotateConnection(connectionGeneration: Long, reason: String) {
        val oldSocket: WebSocket?
        synchronized(lock) {
            if (!started || generation != connectionGeneration) return
            generation++
            oldSocket = socket
            socket = null
            connecting = false
            ready = false
            audioActive = false
            setupTimeoutJob?.cancel()
            setupTimeoutJob = null
        }
        oldSocket?.close(1001, reason)
        connect(isReconnect = true)
    }

    private fun scheduleRotation(connectionGeneration: Long, delayMs: Long) {
        synchronized(lock) {
            goAwayJob?.cancel()
            goAwayJob = scope.launch {
                delay(delayMs)
                rotateConnection(connectionGeneration, "Gemini 会话轮换")
            }
        }
    }

    private fun scheduleIdleClose() {
        synchronized(lock) {
            idleCloseJob?.cancel()
            val idleToken = ++idleCloseToken
            val currentGeneration = generation
            idleCloseJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                val oldSocket: WebSocket?
                synchronized(lock) {
                    if (!started || generation != currentGeneration || idleCloseToken != idleToken) {
                        return@launch
                    }
                    generation++
                    oldSocket = socket
                    socket = null
                    connecting = false
                    ready = false
                    audioActive = false
                    idleCloseJob = null
                    listener.onStatus(GeminiSessionStatus.WAITING_FOR_AUDIO, "等待手机声音")
                }
                oldSocket?.close(1000, "idle")
            }
        }
    }

    private fun sendFrame(webSocket: WebSocket, pcm: ByteArray) {
        if (webSocket.send(audioMessage(pcm))) {
            sentAudioFrames.incrementAndGet()
            sentAudioBytes.addAndGet(pcm.size.toLong())
            listener.onAudioSent(pcm.size)
        } else {
            rejectedAudioFrames.incrementAndGet()
        }
        emitNetworkDiagnostics()
    }

    private fun emitNetworkDiagnostics(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force) {
            while (true) {
                val previous = lastDiagnosticsAtNs.get()
                if (previous != 0L && now - previous < DIAGNOSTICS_INTERVAL_NS) return
                if (lastDiagnosticsAtNs.compareAndSet(previous, now)) break
            }
        } else {
            lastDiagnosticsAtNs.set(now)
        }
        diagnostic(
            "Gemini stats received=${receivedMessages.get()} serverContent=${receivedServerContents.get()} " +
                "source=${receivedSourceTranscriptions.get()} output=${receivedOutputTranscriptions.get()} " +
                "modelTurn=${receivedModelTurns.get()} modelText=${receivedModelTexts.get()} " +
                "invalid=${invalidMessages.get()} " +
                "sentFrames=${sentAudioFrames.get()} sentBytes=${sentAudioBytes.get()} " +
                "sendRejected=${rejectedAudioFrames.get()} queueDropped=${droppedQueuedFrames.get()}",
        )
    }

    private fun diagnostic(message: String) {
        runCatching { listener.onDiagnostics(message) }
    }

    private fun isCurrent(connectionGeneration: Long): Boolean = synchronized(lock) {
        started && generation == connectionGeneration
    }

    private fun cancelTimersLocked() {
        reconnectJob?.cancel()
        setupTimeoutJob?.cancel()
        idleCloseJob?.cancel()
        idleCloseToken++
        goAwayJob?.cancel()
        reconnectJob = null
        setupTimeoutJob = null
        idleCloseJob = null
        goAwayJob = null
    }

    internal companion object {
        const val WEB_SOCKET_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MODEL_ID = "models/gemini-3.5-live-translate-preview"
        const val MAX_QUEUED_FRAMES = 50
        const val SETUP_TIMEOUT_MS = 15_000L
        const val IDLE_CLOSE_MS = 5 * 60 * 1000L
        const val GO_AWAY_MARGIN_MS = 2_000L
        const val DIAGNOSTICS_INTERVAL_NS = 5_000_000_000L
        val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

        fun buildClient(settings: AppSettings): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
            if (settings.proxyMode != ProxyMode.SYSTEM) {
                val type = if (settings.proxyMode == ProxyMode.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
                builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(settings.proxyHost, settings.proxyPort)))
            }
            return builder.build()
        }

        fun webSocketUrl(apiKey: String): String =
            "$WEB_SOCKET_URL?key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}"

        fun setupMessage(targetLanguage: String, resumptionHandle: String?): String {
            val generationConfig = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put(
                    "translationConfig",
                    JSONObject()
                        .put("targetLanguageCode", targetLanguage)
                        .put("echoTargetLanguage", false),
                )
            val sessionResumption = JSONObject().apply {
                if (!resumptionHandle.isNullOrBlank()) put("handle", resumptionHandle)
            }
            return JSONObject()
                .put(
                    "setup",
                    JSONObject()
                        .put("model", MODEL_ID)
                        .put("generationConfig", generationConfig)
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                        .put("sessionResumption", sessionResumption),
                )
                .toString()
        }

        fun audioMessage(pcm: ByteArray): String = JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", Base64.getEncoder().encodeToString(pcm))
                        .put("mimeType", "audio/pcm;rate=16000"),
                ),
            )
            .toString()

        fun audioStreamEndMessage(): String = JSONObject()
            .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
            .toString()

        fun parseDurationMs(value: Any?): Long = when (value) {
            is Number -> value.toLong()
            is String -> {
                val trimmed = value.trim()
                if (trimmed.endsWith("s")) {
                    (trimmed.dropLast(1).toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
                } else {
                    trimmed.toDoubleOrNull()?.toLong() ?: 0L
                }
            }
            else -> 0L
        }

        fun sanitizeError(message: String): String = message
            .replace(Regex("([?&]key=)[^&\\s]+", RegexOption.IGNORE_CASE), "${'$'}1[redacted]")
            .replace(Regex("\\bAIza[0-9A-Za-z_-]{20,}\\b"), "[redacted-api-key]")
            .replace(
                Regex("((?:api[ _-]?key|key)\\s*[:=]\\s*)[^\\s,;]+", RegexOption.IGNORE_CASE),
                "${'$'}1[redacted]",
            )
            .take(240)

        fun serverErrorMessage(error: JSONObject): String {
            val code = error.optInt("code").takeIf { it != 0 }
            val status = error.optString("status").takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) }
            return listOfNotNull(
                "Gemini 返回错误",
                code?.let { "code=$it" },
                status?.let { "status=$it" },
            ).joinToString(" · ")
        }

        fun classifyMessage(message: JSONObject): GeminiMessageKind = when {
            message.has("setupComplete") -> GeminiMessageKind.SETUP_COMPLETE
            message.has("error") -> GeminiMessageKind.SERVER_ERROR
            message.has("serverContent") -> GeminiMessageKind.SERVER_CONTENT
            message.has("sessionResumptionUpdate") -> GeminiMessageKind.SESSION_RESUMPTION
            message.has("goAway") -> GeminiMessageKind.GO_AWAY
            else -> GeminiMessageKind.OTHER
        }

        fun extractModelText(serverContent: JSONObject): String? {
            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return null
            val text = buildString {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.optBoolean("thought")) continue
                    val partText = part.optString("text").takeIf { it.isNotEmpty() }
                    if (partText != null) append(partText)
                }
            }
            return text.takeIf { it.isNotEmpty() }
        }

        fun isTerminalCloseCode(code: Int): Boolean =
            code in 4000..4999 || code in setOf(1002, 1003, 1007, 1008, 1009)
    }
}
