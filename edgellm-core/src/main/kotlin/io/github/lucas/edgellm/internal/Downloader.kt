package io.github.lucas.edgellm.internal

import io.github.lucas.edgellm.DownloadProgress
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal object Downloader {

    /**
     * Downloads [url] to [dest] with resume support. Bytes stream into
     * `dest.part`; only after size and checksum verification does the file
     * move to [dest], so a completed [dest] is always trustworthy.
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        expectedSha256: String?,
    ): Flow<DownloadProgress> = flow {
        if (dest.length() == expectedSize) {
            emit(DownloadProgress(expectedSize, expectedSize))
            return@flow
        }
        dest.parentFile?.mkdirs()

        val part = File(dest.parent, dest.name + ".part")
        var have = part.length()
        if (have > expectedSize) {
            part.delete()
            have = 0
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit // resuming from `have`
                HttpURLConnection.HTTP_OK -> {
                    // Server ignored the Range request; start over.
                    part.delete()
                    have = 0
                }
                else -> error("HTTP ${conn.responseCode} downloading $url")
            }

            conn.inputStream.use { input ->
                FileOutputStream(part, /*append=*/have > 0).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        have += n
                        emit(DownloadProgress(have, expectedSize))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (have != expectedSize) {
            error("Download incomplete: $have of $expectedSize bytes (will resume on retry)")
        }
        if (expectedSha256 != null) {
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                part.delete() // corrupt or wrong file; resuming would be pointless
                error("Checksum mismatch: expected $expectedSha256, got $actual")
            }
        }
        check(part.renameTo(dest)) { "Could not move ${part.name} into place" }
        emit(DownloadProgress(expectedSize, expectedSize))
    }.flowOn(Dispatchers.IO)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
