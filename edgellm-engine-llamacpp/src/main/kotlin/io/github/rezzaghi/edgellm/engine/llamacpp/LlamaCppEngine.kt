package io.github.rezzaghi.edgellm.engine.llamacpp

import io.github.rezzaghi.edgellm.GenerationEvent
import io.github.rezzaghi.edgellm.ModelFormat
import io.github.rezzaghi.edgellm.engine.Backend
import io.github.rezzaghi.edgellm.engine.DeviceProfile
import io.github.rezzaghi.edgellm.engine.EngineCapabilities
import io.github.rezzaghi.edgellm.engine.EngineConfig
import io.github.rezzaghi.edgellm.engine.EngineSession
import io.github.rezzaghi.edgellm.engine.GenerationRequest
import io.github.rezzaghi.edgellm.engine.InferenceEngine
import io.github.rezzaghi.edgellm.engine.ModelFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** llama.cpp engine. Registered via ServiceLoader; not used directly by apps. */
class LlamaCppEngine : InferenceEngine {

    override val id = "llamacpp"

    override val supportedFormats = setOf(ModelFormat.GGUF)

    override fun capabilities(device: DeviceProfile) =
        EngineCapabilities(backends = setOf(Backend.CPU)) // Vulkan later

    override suspend fun load(file: ModelFile, config: EngineConfig): EngineSession {
        val handle = withContext(Dispatchers.IO) {
            LlamaBridge.nativeLoadModel(file.path, config.contextLength)
        }
        check(handle != 0L) { "llama.cpp failed to load ${file.path} (see logcat, tag: edgellm)" }
        return LlamaCppSession(handle)
    }
}

private class LlamaCppSession(private val handle: Long) : EngineSession {

    private val generating = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Held for the whole native generate call; close() takes it before
     * freeing, so the context can never be destroyed mid-decode.
     */
    private val nativeLock = Mutex()

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        check(!closed.get()) { "Session is closed" }
        check(generating.compareAndSet(false, true)) {
            "A generation is already running on this session"
        }

        // The blocking call runs in a child coroutine so that this block
        // reaches awaitClose immediately — otherwise cancellation could
        // never deliver nativeStop while generation is in flight.
        launch(Dispatchers.IO) {
            try {
                val decoder = Utf8StreamDecoder()
                val startMs = System.currentTimeMillis()

                val n = nativeLock.withLock {
                    LlamaBridge.nativeGenerate(
                        handle, request.prompt, request.maxTokens, request.temperature,
                    ) { piece ->
                        val text = decoder.feed(piece)
                        if (text.isNotEmpty()) trySendBlocking(GenerationEvent.Token(text))
                    }
                }

                val tail = decoder.flush()
                if (tail.isNotEmpty()) trySendBlocking(GenerationEvent.Token(tail))

                if (n < 0) {
                    close(IllegalStateException("llama.cpp generation failed: error $n"))
                } else {
                    val secs = (System.currentTimeMillis() - startMs) / 1000.0
                    trySendBlocking(GenerationEvent.Done(n, if (secs > 0) n / secs else 0.0))
                    close()
                }
            } finally {
                generating.set(false)
            }
        }

        awaitClose {
            // Runs on cancellation too: tell native code to stop soon.
            // The worker above keeps running until the C++ loop notices the
            // flag (≤ one token) and exits, releasing nativeLock.
            LlamaBridge.nativeStop(handle)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun applyChatTemplate(
        messages: List<io.github.rezzaghi.edgellm.ChatMessage>,
    ): String? = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Session is closed" }
        LlamaBridge.nativeApplyChatTemplate(
            handle,
            messages.map { it.role.toByteArray() }.toTypedArray(),
            messages.map { it.content.toByteArray() }.toTypedArray(),
            addAssistant = true,
        )?.toString(Charsets.UTF_8)
    }

    override suspend fun tokenCount(text: String): Int = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Session is closed" }
        LlamaBridge.nativeTokenCount(handle, text)
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            LlamaBridge.nativeStop(handle)     // ask any in-flight generate to exit…
            nativeLock.withLock {              // …WAIT until it actually has…
                withContext(Dispatchers.IO) { LlamaBridge.nativeFree(handle) } // …then free
            }
        }
    }
}
