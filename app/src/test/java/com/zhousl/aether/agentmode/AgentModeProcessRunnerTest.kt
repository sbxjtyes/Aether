package com.zhousl.aether.agentmode

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeProcessRunnerTest {
    @Test
    fun `runner drains output and enforces retention limit`() {
        val process = FakeAgentModeProcess(
            input = ByteArrayInputStream("x".repeat(32 * 1024).toByteArray()),
            finishes = true,
            exitCode = 0,
        )

        val result = runAgentModeProcess(
            command = listOf("fake"),
            timeoutMillis = 1_000,
            maxOutputBytes = 2_048,
            processStarter = { process },
        )

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals(2_048, result.output.length)
        assertTrue(result.truncated)
    }

    @Test
    fun `runner force stops timed out command and reports timeout`() {
        val input = AgentModeCloseBlockingInputStream()
        val process = FakeAgentModeProcess(
            input = input,
            finishes = false,
            exitCode = 0,
        )

        val result = runAgentModeProcess(
            command = listOf("fake"),
            timeoutMillis = 10,
            maxOutputBytes = 1_024,
            processStarter = { process },
        )

        assertTrue(result.timedOut)
        assertTrue(process.destroyCalled)
        assertTrue(process.destroyForciblyCalled)
        assertTrue(input.closed)
        assertTrue(result.failureMessage("Input command", 10).contains("timed out after 10 ms"))
    }

    @Test
    fun `failure message preserves bounded command output and truncation marker`() {
        val message = com.zhousl.aether.util.ProcessOutputResult(
            output = "permission denied",
            exitCode = 13,
            timedOut = false,
            truncated = true,
        ).failureMessage("am start", 8_000)

        assertTrue(message.startsWith("permission denied"))
        assertTrue(message.endsWith("[output truncated]"))
    }
}

private class FakeAgentModeProcess(
    private val input: InputStream,
    finishes: Boolean,
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

private class AgentModeCloseBlockingInputStream : InputStream() {
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
