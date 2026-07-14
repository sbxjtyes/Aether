package com.zhousl.aether.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeProcessRunnerTest {
    @Test
    fun `agent mode probe drains both pipes and retains bounded tails`() = runBlocking {
        val process = ProbeDrainProcess(
            stdout = "o".repeat(400_000) + "AGENT_STDOUT_END",
            stderr = "e".repeat(400_000) + "AGENT_STDERR_END",
        )

        val result = withTimeout(2_000) {
            runAgentModeProbeProcess(
                command = listOf("fake"),
                timeoutMillis = 1_500,
                processStarter = { process },
            )
        }

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.endsWith("AGENT_STDOUT_END"))
        assertTrue(result.stderr.endsWith("AGENT_STDERR_END"))
        assertTrue(result.stdout.length <= 256 * 1024)
        assertTrue(result.stderr.length <= 256 * 1024)
    }
}

private class ProbeDrainProcess(
    stdout: String,
    stderr: String,
) : Process() {
    private val streamsDrained = CountDownLatch(2)
    private val stdoutStream = ProbeEndAwareInputStream(stdout.toByteArray(), streamsDrained)
    private val stderrStream = ProbeEndAwareInputStream(stderr.toByteArray(), streamsDrained)
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

private class ProbeEndAwareInputStream(
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
