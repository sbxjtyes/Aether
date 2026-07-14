package com.zhousl.aether.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.io.DEFAULT_BUFFER_SIZE

internal fun InputStream.readUtf8TextWithLimit(maxBytes: Int): String {
    require(maxBytes > 0) { "maxBytes must be positive." }
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val remaining = maxBytes - totalBytes
        val read = read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (read < 0) break
        if (read == 0) continue
        totalBytes += read
        require(totalBytes <= maxBytes) {
            "The selected file is too large (maximum ${maxBytes / (1024 * 1024)} MiB)."
        }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}
