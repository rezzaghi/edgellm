package io.github.lucas.edgellm.engine.llamacpp

import io.github.lucas.edgellm.GenerationEvent
import io.github.lucas.edgellm.ModelFormat
import io.github.lucas.edgellm.engine.Backend
import io.github.lucas.edgellm.engine.DeviceProfile
import io.github.lucas.edgellm.engine.EngineCapabilities
import io.github.lucas.edgellm.engine.EngineConfig
import io.github.lucas.edgellm.engine.EngineSession
import io.github.lucas.edgellm.engine.GenerationRequest
import io.github.lucas.edgellm.engine.InferenceEngine
import io.github.lucas.edgellm.engine.ModelFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        check(!closed.get()) { "Session is closed" }
        check(generating.compareAndSet(false, true)) {
            "A generation is already running on this session"
        }

        val decoder = Utf8StreamDecoder()
        val startMs = System.currentTimeMillis()

        val n = LlamaBridge.nativeGenerate(
            handle, request.prompt, request.maxTokens, request.temperature,
        ) { piece ->
            val text = decoder.feed(piece)
            if (text.isNotEmpty()) trySendBlocking(GenerationEvent.Token(text))
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

        awaitClose {
            // Runs on cancellation too: tell native code to stop soon.
            LlamaBridge.nativeStop(handle)
            generating.set(false)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun tokenCount(text: String): Int = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Session is closed" }
        LlamaBridge.nativeTokenCount(handle, text)
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            LlamaBridge.nativeStop(handle)
            withContext(Dispatchers.IO) { LlamaBridge.nativeFree(handle) }
        }
    }
}
