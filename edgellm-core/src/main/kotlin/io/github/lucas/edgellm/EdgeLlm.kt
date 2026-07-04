package io.github.lucas.edgellm

import android.content.Context
import io.github.lucas.edgellm.engine.DeviceProfile
import io.github.lucas.edgellm.engine.EngineConfig
import io.github.lucas.edgellm.engine.EngineSession
import io.github.lucas.edgellm.engine.GenerationRequest
import io.github.lucas.edgellm.engine.ModelFile
import io.github.lucas.edgellm.internal.DeviceProfiler
import io.github.lucas.edgellm.internal.EngineRegistry
import io.github.lucas.edgellm.internal.FitChecker
import java.io.File
import kotlinx.coroutines.flow.Flow

/** Entry point of the SDK. Obtain via [EdgeLlm.create]. */
class EdgeLlm private constructor(private val context: Context) {

    /** Snapshot of this device's hardware profile (RAM is point-in-time). */
    fun deviceProfile(): DeviceProfile = DeviceProfiler.profile(context)

    /** Can [model] run on this device right now? Uses current available RAM. */
    fun checkFit(model: ModelSpec): Fit = FitChecker.check(deviceProfile(), model)

    /** Where this model lives (or will live) on disk. */
    fun modelFile(model: ModelSpec): File =
        File(context.getExternalFilesDir("models"), "${model.id}.${model.format.extension}")

    fun isDownloaded(model: ModelSpec): Boolean =
        modelFile(model).length() == model.sizeBytes

    /**
     * Loads a downloaded model and returns a session ready to generate.
     * Blocking-heavy; safe to call from any dispatcher (engine offloads to IO).
     */
    suspend fun load(model: ModelSpec, config: EngineConfig = EngineConfig()): EdgeLlmSession {
        val file = modelFile(model)
        require(file.exists()) { "Model ${model.id} is not downloaded (expected ${file.path})" }

        val engine = EngineRegistry.engineFor(model.format)
            ?: error(
                "No engine supports ${model.format}. " +
                    "Add a runtimeOnly dependency on an edgellm-engine-* module."
            )
        return EdgeLlmSession(engine.load(ModelFile(file.absolutePath, model.format), config))
    }

    companion object {
        fun create(context: Context): EdgeLlm = EdgeLlm(context.applicationContext)
    }
}

/** A loaded model, ready to generate. Close when done to free native memory. */
class EdgeLlmSession internal constructor(private val engine: EngineSession) {

    /**
     * Streams generation events. Cancelling collection stops generation.
     * One generation at a time per session.
     */
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<GenerationEvent> =
        engine.generate(GenerationRequest(prompt, maxTokens, temperature))

    suspend fun tokenCount(text: String): Int = engine.tokenCount(text)

    suspend fun close() = engine.close()
}
