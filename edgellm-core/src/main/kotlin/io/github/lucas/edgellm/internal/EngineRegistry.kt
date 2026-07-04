package io.github.lucas.edgellm.internal

import io.github.lucas.edgellm.ModelFormat
import io.github.lucas.edgellm.engine.InferenceEngine
import java.util.ServiceLoader

internal object EngineRegistry {

    private val engines: List<InferenceEngine> by lazy {
        ServiceLoader.load(InferenceEngine::class.java).toList()
    }

    fun engineFor(format: ModelFormat): InferenceEngine? =
        engines.firstOrNull { format in it.supportedFormats }

    fun all(): List<InferenceEngine> = engines
}
