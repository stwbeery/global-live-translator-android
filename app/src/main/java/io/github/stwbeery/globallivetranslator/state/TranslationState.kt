package io.github.stwbeery.globallivetranslator.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TranslationPhase {
    IDLE,
    CONNECTING,
    WAITING_FOR_AUDIO,
    TRANSLATING,
    RECONNECTING,
    STOPPING,
    ERROR,
}

data class TranslationUiState(
    val phase: TranslationPhase = TranslationPhase.IDLE,
    val status: String = "未启动",
    val sourceText: String = "",
    val translatedText: String = "",
    val error: String? = null,
    val bytesSent: Long = 0,
)

object TranslationStateStore {
    private val mutableState = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = mutableState.asStateFlow()

    fun setPhase(phase: TranslationPhase, status: String, error: String? = null) {
        mutableState.update { it.copy(phase = phase, status = status, error = error) }
    }

    fun setCaption(source: String, translated: String) {
        mutableState.update { it.copy(sourceText = source, translatedText = translated, error = null) }
    }

    fun addBytesSent(bytes: Int) {
        mutableState.update { it.copy(bytesSent = it.bytesSent + bytes) }
    }

    fun reset() {
        mutableState.value = TranslationUiState()
    }
}
