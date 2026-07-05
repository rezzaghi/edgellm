package io.github.rezzaghi.edgellm

/** Streamed events during generation. */
sealed interface GenerationEvent {
    /** A decoded piece of output text (may be less than a whole word). */
    data class Token(val text: String) : GenerationEvent

    /** Generation finished normally (end-of-generation token or maxTokens). */
    data class Done(val totalTokens: Int, val tokensPerSec: Double) : GenerationEvent
}
