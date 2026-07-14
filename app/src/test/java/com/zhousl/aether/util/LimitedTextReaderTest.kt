package com.zhousl.aether.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LimitedTextReaderTest {
    @Test
    fun readsContentAtLimit() {
        val input = ByteArrayInputStream("Aether".toByteArray())

        assertEquals("Aether", input.readUtf8TextWithLimit(6))
    }

    @Test
    fun rejectsContentBeyondLimitWithoutReadingWholeStream() {
        val input = CountingInputStream(ByteArray(1_000_000) { 'a'.code.toByte() })

        try {
            input.readUtf8TextWithLimit(32)
            fail("Expected oversized content to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        check(input.bytesRead <= 33)
    }

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead: Int = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { read ->
                if (read > 0) bytesRead += read
            }
    }
}
