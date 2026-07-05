package io.github.rezzaghi.edgellm

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}
