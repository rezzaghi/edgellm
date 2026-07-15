package io.github.rezzaghi.edgellm.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rezzaghi.edgellm.ChatMessage
import io.github.rezzaghi.edgellm.EdgeLlmSession
import io.github.rezzaghi.edgellm.Fit
import io.github.rezzaghi.edgellm.GenerationEvent
import io.github.rezzaghi.edgellm.ModelSpec
import io.github.rezzaghi.edgellm.engine.Backend
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data class NeedsDownload(val fit: Fit, val downloaded: Boolean) : UiState
    data class Downloading(val fraction: Float) : UiState
    data object LoadingModel : UiState
    data class Ready(val backend: Backend) : UiState
    data class Error(val message: String) : UiState
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = (app as SampleApp).edgeLlm

    val models: List<ModelSpec> = llm.catalog()

    var model: ModelSpec = models.first()
        private set

    private val _state = MutableStateFlow<UiState>(initialStateFor(models.first()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private var session: EdgeLlmSession? = null
    private var generation: Job? = null

    fun selectModel(spec: ModelSpec) {
        generation?.cancel()
        session?.close()
        session = null
        model = spec
        _messages.value = emptyList()
        _generating.value = false
        _state.value = initialStateFor(spec)
    }

    fun prepare() {
        val spec = model
        viewModelScope.launch {
            when (val fit = llm.checkFit(spec)) {
                is Fit.Ok, is Fit.TightRam -> Unit
                is Fit.WontFit -> {
                    _state.value = UiState.Error(
                        "Model needs ~${fit.requiredMb}MB of RAM; " +
                            "only ${fit.availableMb}MB available"
                    )
                    return@launch
                }
            }
            try {
                llm.download(spec).collect { progress ->
                    _state.value = UiState.Downloading(progress.fraction)
                }
                _state.value = UiState.LoadingModel
                val newSession = llm.load(spec)
                session = newSession
                _state.value = UiState.Ready(newSession.backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Something went wrong")
            }
        }
    }

    fun send(text: String) {
        val activeSession = session ?: return
        if (_generating.value || text.isBlank()) return

        val history = _messages.value + ChatMessage.user(text)
        _messages.value = history
        _generating.value = true

        generation = viewModelScope.launch {
            var reply = ""
            try {
                activeSession.chat(history).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            reply += event.text
                            _messages.value = history + ChatMessage.assistant(reply)
                        }
                        is GenerationEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.value = history + ChatMessage.assistant("[error: ${e.message}]")
            } finally {
                _generating.value = false
            }
        }
    }

    fun stop() {
        generation?.cancel()
        _generating.value = false
    }

    override fun onCleared() {
        session?.close()
    }

    private fun initialStateFor(spec: ModelSpec): UiState =
        UiState.NeedsDownload(llm.checkFit(spec), llm.isDownloaded(spec))
}
