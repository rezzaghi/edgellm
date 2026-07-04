package io.github.lucas.edgellm

/**
 * Declares a model the app wants to use. In a later milestone the Gradle
 * plugin generates these; for now they are written by hand.
 */
data class ModelSpec(
    /** Stable identifier; also used as the on-disk file name. */
    val id: String,
    /** Direct download URL (e.g. a Hugging Face resolve link). */
    val url: String,
    /** Expected SHA-256 of the file, or null to skip verification (dev only). */
    val sha256: String?,
    /** Exact file size in bytes; used for fit checks and download validation. */
    val sizeBytes: Long,
    val format: ModelFormat = ModelFormat.GGUF,
)

enum class ModelFormat(val extension: String) {
    GGUF("gguf"),
}
