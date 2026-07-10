package io.github.rezzaghi.edgellm.engine.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamDecoderTest {

    private val decoder = Utf8StreamDecoder()

    // U+1F680 (rocket): a 4-byte UTF-8 sequence, F0 9F 9A 80
    private val fourByteChar = String(Character.toChars(0x1F680))
    private val fourByteSeq =
        byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x9A.toByte(), 0x80.toByte())

    @Test
    fun asciiPassesThrough() {
        assertEquals("Hello", decoder.feed("Hello".toByteArray()))
    }

    @Test
    fun twoByteCharSplitAcrossChunks() {
        // e-acute = C3 A9
        assertEquals("", decoder.feed(byteArrayOf(0xC3.toByte())))
        assertEquals("é", decoder.feed(byteArrayOf(0xA9.toByte())))
    }

    @Test
    fun fourByteCharSplitAtEveryPosition() {
        for (split in 1..3) {
            val d = Utf8StreamDecoder()
            assertEquals("", d.feed(fourByteSeq.copyOfRange(0, split)))
            assertEquals(fourByteChar, d.feed(fourByteSeq.copyOfRange(split, 4)))
        }
    }

    @Test
    fun completeTextWithTrailingPartialChar() {
        val chunk = "Hi".toByteArray() + byteArrayOf(0xF0.toByte())
        assertEquals("Hi", decoder.feed(chunk))
        assertEquals(
            fourByteChar,
            decoder.feed(byteArrayOf(0x9F.toByte(), 0x9A.toByte(), 0x80.toByte())),
        )
    }

    @Test
    fun multiByteTextInOneChunk() {
        val text = "olá, ação — 日本語"
        assertEquals(text, decoder.feed(text.toByteArray()))
    }

    @Test
    fun flushEmitsNothingWhenEmpty() {
        decoder.feed("complete".toByteArray())
        assertEquals("", decoder.flush())
    }

    @Test
    fun flushDecodesIncompleteTailAsReplacement() {
        decoder.feed(byteArrayOf(0xF0.toByte(), 0x9F.toByte()))
        assertEquals("�", decoder.flush())
    }

    @Test
    fun flushResetsState() {
        decoder.feed(byteArrayOf(0xC3.toByte()))
        decoder.flush()
        assertEquals("ok", decoder.feed("ok".toByteArray()))
    }

    @Test
    fun tokenStreamSimulation() {
        val pieces = listOf("The", " sky", " is", " blue", ".")
        val out = StringBuilder()
        pieces.forEach { out.append(decoder.feed(it.toByteArray())) }
        out.append(decoder.flush())
        assertEquals("The sky is blue.", out.toString())
    }
}
