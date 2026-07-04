package io.github.lucas.edgellm.engine.llamacpp

/**
 * Thin JNI bridge over llama.cpp — spike version.
 *
 * Calls are blocking; run them off the main thread. Token pieces arrive as
 * raw UTF-8 bytes because a piece can end in the middle of a multi-byte
 * character — accumulate bytes and decode the whole buffer for display.
 */
object LlamaBridge {

    init {
        System.loadLibrary("edgellm_jni")
    }

    fun interface TokenCallback {
        fun onToken(bytes: ByteArray)
    }

    /** Returns a native session handle, or 0 on failure. */
    external fun nativeLoadModel(path: String, nCtx: Int): Long

    /** Blocks until done. Returns tokens generated, or a negative error code. */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback,
    ): Int

    /** Token count of [text] under the loaded model's tokenizer, or negative on error. */
    external fun nativeTokenCount(handle: Long, text: String): Int

    /** Requests an in-flight generate on this handle to stop soon. Thread-safe. */
    external fun nativeStop(handle: Long)

    /** Frees the model and context. The handle is invalid afterwards. */
    external fun nativeFree(handle: Long)
}
