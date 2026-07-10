package io.github.rezzaghi.edgellm.engine

import io.github.rezzaghi.edgellm.GenerationEvent
import io.github.rezzaghi.edgellm.ModelFormat
import kotlinx.coroutines.flow.Flow

/**
 * SPI implemented by engine modules (llamacpp, litert, …) and discovered via
 * [java.util.ServiceLoader].
 *
 * UNSTABLE until 1.0 — implementors should expect breaking changes.
 */
interface InferenceEngine {
    /** Stable engine id, e.g. "llamacpp". */
    val id: String

    val supportedFormats: Set<ModelFormat>

    /** What this engine can do on this particular device. */
    fun capabilities(device: DeviceProfile): EngineCapabilities

    suspend fun load(file: ModelFile, config: EngineConfig): EngineSession
}

/**
 * A loaded model. Contract:
 * - one active [generate] at a time; a second concurrent call is a caller bug
 * - cancelling the Flow's collection MUST stop native generation promptly
 */
interface EngineSession {
    /** The backend actually serving this session (verified, never AUTO). */
    val backend: Backend

    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    /**
     * Formats [messages] with the model's own chat template (e.g. from GGUF
     * metadata), ready to pass to [generate]. Null if the model has none.
     */
    suspend fun applyChatTemplate(messages: List<io.github.rezzaghi.edgellm.ChatMessage>): String?

    /** Token count of [text] under this model's tokenizer. */
    suspend fun tokenCount(text: String): Int

    suspend fun close()
}

data class ModelFile(
    val path: String,
    val format: ModelFormat,
)

data class EngineConfig(
    val contextLength: Int = 2048,
    /** 0 = let the engine pick. */
    val threads: Int = 0,
    /**
     * AUTO lets the SDK route per device. Engines never receive AUTO —
     * core resolves it to a concrete backend before load.
     */
    val backend: Backend = Backend.AUTO,
)

data class GenerationRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    /** <= 0 selects greedy (deterministic) sampling. */
    val temperature: Float = 0.7f,
)

data class EngineCapabilities(
    val backends: Set<Backend>,
)

enum class Backend { AUTO, CPU, OPENCL, VULKAN, NPU }

data class DeviceProfile(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val socModel: String,
    val socManufacturer: String,
    val abis: List<String>,
)
