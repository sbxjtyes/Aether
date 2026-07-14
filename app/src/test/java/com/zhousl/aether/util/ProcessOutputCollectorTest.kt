package com.zhousl.aether.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessOutputCollectorTest {
    @Test
    fun `collector drains full stream while retaining only configured bytes`() {
        val process = FakeProcess(
            input = ByteArrayInputStream("x".repeat(64 * 1024).toByteArray()),
            finishes = true,
            exitCode = 7,
        )

        val result = awaitMergedProcessOutput(
            process = process,
            timeoutMillis = 1_000,
            maxOutputBytes = 1_024,
        )

        assertFalse(result.timedOut)
        assertEquals(7, result.exitCode)
        assertEquals(1_024, result.output.length)
        assertTrue(result.truncated)
    }

    @Test
    fun `timeout force stops process and closes blocked output reader`() {
        val input = CloseBlockingInputStream()
        val process = FakeProcess(
            input = input,
            finishes = false,
            exitCode = 0,
        )

        val result = awaitMergedProcessOutput(
            process = process,
            timeoutMillis = 10,
            maxOutputBytes = 1_024,
        )

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(process.destroyCalled)
        assertTrue(process.destroyForciblyCalled)
        assertTrue(input.closed)
    }
}

private class FakeProcess(
    private val input: InputStream,
    private val finishes: Boolean,
    private val exitCode: Int,
) : Process() {
    @Volatile
    private var alive = !finishes

    var destroyCalled = false
        private set
    var destroyForciblyCalled = false
        private set

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exitCode

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("Process is still running.")
        return exitCode
    }

    override fun destroy() {
        destroyCalled = true
    }

    override fun destroyForcibly(): Process {
        destroyForciblyCalled = true
        alive = false
        return this
    }

    override fun isAlive(): Boolean = alive
}

private class CloseBlockingInputStream : InputStream() {
    private val lock = Object()

    @Volatile
    var closed = false
        private set

    override fun read(): Int {
        synchronized(lock) {
            while (!closed) lock.wait()
        }
        return -1
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
    }
}
