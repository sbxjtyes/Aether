package com.zhousl.aether.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RootProcessRunnerTest {
    @Test
    fun `drains stdout and stderr before waiting for process completion`() = runBlocking {
        val process = DrainBeforeExitProcess(
            stdout = "o".repeat(400_000) + "STDOUT_END",
            stderr = "e".repeat(400_000) + "STDERR_END",
        )

        val result = withTimeout(2_000) {
            runRootProcess(listOf("fake"), 1_500) { process }
        }

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.endsWith("STDOUT_END"))
        assertTrue(result.stderr.endsWith("STDERR_END"))
        assertTrue(result.stdout.length <= 256 * 1024)
        assertTrue(result.stderr.length <= 256 * 1024)
    }

    @Test
    fun `timeout destroys a process and returns timed out result`() = runBlocking {
        val process = BlockingProcess()

        val result = runRootProcess(listOf("fake"), 50) { process }

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(process.destroyed.get())
        assertFalse(process.isAlive)
    }

    @Test
    fun `caller cancellation is propagated and destroys process`() = runBlocking {
        val process = BlockingProcess()

        try {
            withTimeout(50) {
                runRootProcess(listOf("fake"), 10_000) { process }
            }
            fail("Expected caller timeout to cancel the process runner")
        } catch (_: TimeoutCancellationException) {
            // Expected: an outer cancellation must not be converted into a command timeout result.
        }

        assertTrue(process.destroyed.get())
        assertFalse(process.isAlive)
    }
}

private class DrainBeforeExitProcess(
    stdout: String,
    stderr: String,
) : Process() {
    private val streamsDrained = CountDownLatch(2)
    private val stdoutStream = EndAwareInputStream(stdout.toByteArray(), streamsDrained)
    private val stderrStream = EndAwareInputStream(stderr.toByteArray(), streamsDrained)
    private val alive = AtomicBoolean(true)

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = stdoutStream
    override fun getErrorStream(): InputStream = stderrStream
    override fun waitFor(): Int {
        streamsDrained.await()
        alive.set(false)
        return 0
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val finished = streamsDrained.await(timeout, unit)
        if (finished) alive.set(false)
        return finished
    }
    override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
    override fun destroy() {
        alive.set(false)
        while (streamsDrained.count > 0) streamsDrained.countDown()
    }
    override fun destroyForcibly(): Process = apply { destroy() }
    override fun isAlive(): Boolean = alive.get()
}

private class EndAwareInputStream(
    bytes: ByteArray,
    private val drainedLatch: CountDownLatch,
) : ByteArrayInputStream(bytes) {
    private val reported = AtomicBoolean(false)

    override fun read(): Int = super.read().also(::reportIfFinished)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also(::reportIfFinished)

    override fun close() {
        reportIfFinished(-1)
        super.close()
    }

    private fun reportIfFinished(value: Int) {
        if (value < 0 && reported.compareAndSet(false, true)) drainedLatch.countDown()
    }
}

private class BlockingProcess : Process() {
    private val exitLatch = CountDownLatch(1)
    val destroyed = AtomicBoolean(false)

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int {
        exitLatch.await()
        return -1
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exitLatch.await(timeout, unit)
    override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else -1
    override fun destroy() {
        destroyed.set(true)
        exitLatch.countDown()
    }
    override fun destroyForcibly(): Process = apply { destroy() }
    override fun isAlive(): Boolean = exitLatch.count > 0
}
