package io.github.lucas.edgellm.engine

import io.github.lucas.edgellm.GenerationEvent
import io.github.lucas.edgellm.ModelFormat
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
    fun generate(request: GenerationRequest): Flow<GenerationEvent>

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

enum class Backend { CPU, VULKAN, NPU }

data class DeviceProfile(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val socModel: String,
    val abis: List<String>,
)
