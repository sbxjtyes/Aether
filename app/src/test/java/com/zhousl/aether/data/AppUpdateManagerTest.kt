package com.zhousl.aether.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun updateCacheRemovesInterruptedDownloadsAndKeepsOnlyNewestApk() {
        val directory = Files.createTempDirectory("aether-update-cache").toFile()
        try {
            val olderApk = directory.resolve("Aether-1.apk").apply {
                writeText("old")
                setLastModified(1_000L)
            }
            val newerApk = directory.resolve("Aether-2.APK").apply {
                writeText("new")
                setLastModified(2_000L)
            }
            val interrupted = directory.resolve(".Aether-3.apk.random.part").apply {
                writeText("partial")
            }
            val unrelated = directory.resolve("notes.txt").apply { writeText("leave me") }

            pruneUpdateCache(directory)

            assertFalse(olderApk.exists())
            assertTrue(newerApk.exists())
            assertFalse(interrupted.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun updateCachePreservesRequestedApkDuringReplacement() {
        val directory = Files.createTempDirectory("aether-update-cache-keep").toFile()
        try {
            val requestedApk = directory.resolve("Aether-current.apk").apply {
                writeText("existing")
                setLastModified(1_000L)
            }
            val newerOtherApk = directory.resolve("Aether-other.apk").apply {
                writeText("other")
                setLastModified(2_000L)
            }

            pruneUpdateCache(directory, keepFile = requestedApk)

            assertTrue(requestedApk.exists())
            assertFalse(newerOtherApk.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeDownloadCleanupDoesNotDeleteOtherPartialDownloads() {
        val directory = Files.createTempDirectory("aether-update-cache-active").toFile()
        try {
            val concurrentPartial = directory.resolve(".Aether-other.apk.random.part").apply {
                writeText("downloading")
            }

            pruneUpdateCache(directory, deletePartialFiles = false)

            assertTrue(concurrentPartial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun comparesSemanticVersions() {
        assertTrue(isVersionNewer("v1.2.1", "1.2.0"))
        assertTrue(isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(isVersionNewer("1.2.0", "1.2.0"))
        assertFalse(isVersionNewer("1.1.9", "1.2.0"))
    }

    @Test
    fun ignoresTagPrefixAndBuildMetadata() {
        assertTrue(isVersionNewer("V1.0.1+5", "1.0.0"))
        assertFalse(isVersionNewer("v1.0.0-beta01", "1.0.0"))
    }

    @Test
    fun rejectsBodyWhoseDeclaredLengthExceedsLimitBeforeReading() {
        val input = CountingInputStream("payload".toByteArray())

        val failure = runCatching {
            readUtf8WithLimit(
                input = input,
                declaredLength = 9,
                maxBytes = 8,
                tooLargeMessage = "too large",
            )
        }.exceptionOrNull()

        assertEquals("too large", failure?.message)
        assertEquals(0, input.readCount)
    }

    @Test
    fun rejectsChunkedBodyWhenActualBytesExceedLimit() {
        val output = ByteArrayOutputStream()

        val failure = runCatching {
            copyWithLimit(
                input = ByteArrayInputStream(ByteArray(9) { it.toByte() }),
                output = output,
                declaredLength = -1,
                maxBytes = 8,
                tooLargeMessage = "too large",
            )
        }.exceptionOrNull()

        assertEquals("too large", failure?.message)
        assertEquals(0, output.size())
    }

    @Test
    fun checksCancellationBeforeEachBlockingRead() {
        var checks = 0
        val input = object : ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 2)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, DEFAULT_BUFFER_SIZE))
        }

        val failure = runCatching {
            copyWithLimit(
                input = input,
                output = ByteArrayOutputStream(),
                declaredLength = -1,
                maxBytes = DEFAULT_BUFFER_SIZE.toLong() * 3,
                tooLargeMessage = "too large",
                ensureActive = {
                    checks += 1
                    if (checks == 2) error("cancelled")
                },
            )
        }.exceptionOrNull()

        assertEquals("cancelled", failure?.message)
        assertEquals(2, checks)
    }

    @Test
    fun reportsUnknownProgressWithoutContentLength() {
        var progress: Float? = 1f

        val copied = copyWithLimit(
            input = ByteArrayInputStream("abc".toByteArray()),
            output = ByteArrayOutputStream(),
            declaredLength = -1,
            maxBytes = 8,
            tooLargeMessage = "too large",
            onProgress = { progress = it },
        )

        assertEquals(3L, copied)
        assertNull(progress)
    }
}

private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var readCount: Int = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        readCount += 1
        return super.read(buffer, offset, length)
    }
}
