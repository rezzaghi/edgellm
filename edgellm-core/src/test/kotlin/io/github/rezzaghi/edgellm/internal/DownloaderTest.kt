package io.github.rezzaghi.edgellm.internal

import io.github.rezzaghi.edgellm.DownloadProgress
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = Random(seed = 42).nextBytes(100_000)
    private val sha256 = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private lateinit var server: MockWebServer
    private var requests = 0
    private var lastRangeHeader: String? = null
    private var honorRange = true

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests++
                val range = request.getHeader("Range")
                lastRangeHeader = range
                return if (range != null && honorRange) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    MockResponse()
                        .setResponseCode(206)
                        .setBody(Buffer().write(content, from, content.size - from))
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(content))
                }
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private val url get() = server.url("/model.gguf").toString()

    private fun download(dest: File, sha: String? = sha256): List<DownloadProgress> =
        runBlocking {
            Downloader.download(url, dest, content.size.toLong(), sha).toList()
        }

    @Test
    fun freshDownloadDeliversVerifiedFile() {
        val dest = File(tmp.root, "model.gguf")
        val progress = download(dest)

        assertArrayEquals(content, dest.readBytes())
        assertFalse(File(tmp.root, "model.gguf.part").exists())
        assertEquals(1.0f, progress.last().fraction, 0.0f)
    }

    @Test
    fun alreadyDownloadedSkipsNetwork() {
        val dest = File(tmp.root, "model.gguf")
        dest.writeBytes(content)

        val progress = download(dest)

        assertEquals(0, requests)
        assertEquals(1, progress.size)
        assertEquals(1.0f, progress.last().fraction, 0.0f)
    }

    @Test
    fun resumesFromPartFile() {
        val dest = File(tmp.root, "model.gguf")
        File(tmp.root, "model.gguf.part").writeBytes(content.copyOfRange(0, 40_000))

        download(dest)

        assertEquals("bytes=40000-", lastRangeHeader)
        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        honorRange = false
        val dest = File(tmp.root, "model.gguf")
        File(tmp.root, "model.gguf.part").writeBytes(content.copyOfRange(0, 40_000))

        download(dest)

        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun oversizedPartFileIsDiscarded() {
        val dest = File(tmp.root, "model.gguf")
        File(tmp.root, "model.gguf.part").writeBytes(ByteArray(content.size + 1))

        download(dest)

        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun checksumMismatchDeletesPartAndThrows() {
        val dest = File(tmp.root, "model.gguf")

        val result = runCatching { download(dest, sha = "0".repeat(64)) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Checksum mismatch"))
        assertFalse(dest.exists())
        assertFalse(File(tmp.root, "model.gguf.part").exists())
    }

    @Test
    fun nullShaSkipsVerification() {
        val dest = File(tmp.root, "model.gguf")
        download(dest, sha = null)
        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun progressIsMonotonic() {
        val dest = File(tmp.root, "model.gguf")
        val fractions = download(dest).map { it.fraction }
        assertEquals(fractions.sorted(), fractions)
    }
}
