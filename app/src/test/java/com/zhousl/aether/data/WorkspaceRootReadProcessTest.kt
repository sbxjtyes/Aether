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

class WorkspaceRootReadProcessTest {
    @Test
    fun `root read process drains output while retaining only configured bytes`() = runBlocking {
        val process = WorkspaceFinishedProcess(
            output = "x".repeat(64 * 1024),
            exitCode = 0,
        )

        val result = runWorkspaceRootReadProcess(
            command = listOf("fake"),
            timeoutMillis = 1_000,
            maxOutputBytes = 1_024,
            processStarter = { process },
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals(1_024, result.stdout.length)
        assertTrue(result.truncated)
    }

    @Test
    fun `root read timeout destroys process and closes its streams`() = runBlocking {
        val process = WorkspaceBlockingProcess()

        val result = runWorkspaceRootReadProcess(
            command = listOf("fake"),
            timeoutMillis = 25,
            maxOutputBytes = 1_024,
            processStarter = { process },
        )

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
        assertTrue(process.destroyed.get())
        assertFalse(process.isAlive)
    }

    @Test
    fun `caller cancellation is propagated and destroys root read process`() = runBlocking {
        val process = WorkspaceBlockingProcess()

        try {
            withTimeout(50) {
                runWorkspaceRootReadProcess(
                    command = listOf("fake"),
                    timeoutMillis = 10_000,
                    maxOutputBytes = 1_024,
                    processStarter = { process },
                )
            }
            fail("Expected caller timeout to cancel the root read process")
        } catch (_: TimeoutCancellationException) {
            // Expected: cancellation remains cancellation instead of becoming a command failure.
        }

        assertTrue(process.destroyed.get())
        assertFalse(process.isAlive)
    }
}

private class WorkspaceFinishedProcess(
    output: String,
    private val exitCode: Int,
) : Process() {
    private val input = ByteArrayInputStream(output.toByteArray())

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = exitCode
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
    override fun exitValue(): Int = exitCode
    override fun destroy() = Unit
    override fun destroyForcibly(): Process = this
    override fun isAlive(): Boolean = false
}

private class WorkspaceBlockingProcess : Process() {
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
