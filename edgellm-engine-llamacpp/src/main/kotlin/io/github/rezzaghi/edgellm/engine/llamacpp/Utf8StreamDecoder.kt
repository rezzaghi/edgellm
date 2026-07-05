package io.github.rezzaghi.edgellm.engine.llamacpp

/**
 * Decodes a stream of UTF-8 byte chunks whose boundaries may fall inside a
 * multi-byte character (token pieces from llama.cpp routinely do this).
 * Bytes belonging to an incomplete trailing character are held back until
 * the next chunk completes them.
 */
internal class Utf8StreamDecoder {

    private var pending = ByteArray(0)

    fun feed(chunk: ByteArray): String {
        val buf = pending + chunk
        val cut = completeBoundary(buf)
        pending = buf.copyOfRange(cut, buf.size)
        return String(buf, 0, cut, Charsets.UTF_8)
    }

    /** Emits whatever is left (invalid sequences become replacement chars). */
    fun flush(): String {
        val tail = String(pending, Charsets.UTF_8)
        pending = ByteArray(0)
        return tail
    }

    /** Index up to which [b] is safe to decode. */
    private fun completeBoundary(b: ByteArray): Int {
        if (b.isEmpty()) return 0

        // Walk back over trailing continuation bytes (10xxxxxx) to the lead byte.
        var lead = b.size - 1
        var steps = 0
        while (lead >= 0 && steps < 3 && (b[lead].toInt() and 0xC0) == 0x80) {
            lead--
            steps++
        }
        if (lead < 0) return b.size // continuations only; nothing sane to hold back

        val expected = when {
            (b[lead].toInt() and 0x80) == 0 -> 1 // ASCII
            (b[lead].toInt() and 0xF8) == 0xF0 -> 4
            (b[lead].toInt() and 0xF0) == 0xE0 -> 3
            (b[lead].toInt() and 0xE0) == 0xC0 -> 2
            else -> 1 // invalid lead; decode as-is
        }
        val have = b.size - lead
        return if (have < expected) lead else b.size
    }
}
