package com.zhousl.aether.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class ProcessOutputResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val truncated: Boolean,
)

/**
 * Drains a merged process output stream concurrently so a full pipe cannot block process exit.
 * The reader keeps draining after [maxOutputBytes] but stops retaining bytes, bounding memory use.
 */
internal fun awaitMergedProcessOutput(
    process: Process,
    timeoutMillis: Long,
    maxOutputBytes: Int,
): ProcessOutputResult {
    require(timeoutMillis > 0L) { "timeoutMillis must be greater than 0." }
    require(maxOutputBytes > 0) { "maxOutputBytes must be greater than 0." }

    runCatching { process.outputStream.close() }
    val collector = BoundedStreamCollector(process.inputStream, maxOutputBytes)
    val readerThread = thread(
        start = true,
        isDaemon = true,
        name = "aether-process-output",
        block = collector::drain,
    )

    val finished = try {
        process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        terminateProcess(process)
        closeProcessStreams(process)
        readerThread.interrupt()
        Thread.currentThread().interrupt()
        throw interrupted
    }

    if (!finished) terminateProcess(process)

    readerThread.join(ProcessReaderJoinMillis)
    if (readerThread.isAlive) {
        closeProcessStreams(process)
        readerThread.interrupt()
        readerThread.join(ProcessReaderCloseJoinMillis)
    }
    closeProcessStreams(process)

    return ProcessOutputResult(
        output = collector.text(),
        exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
        timedOut = !finished,
        truncated = collector.truncated,
    )
}

private class BoundedStreamCollector(
    private val input: InputStream,
    private val maxBytes: Int,
) {
    private val retained = ByteArrayOutputStream(minOf(maxBytes, DefaultProcessReadBufferBytes))
    private val retainedLock = Any()

    @Volatile
    var truncated: Boolean = false
        private set

    fun drain() {
        val buffer = ByteArray(DefaultProcessReadBufferBytes)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                synchronized(retainedLock) {
                    val remaining = maxBytes - retained.size()
                    if (remaining > 0) retained.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                }
            }
        } catch (_: Exception) {
            // Stream closure is expected when a process times out or the caller is interrupted.
        }
    }

    fun text(): String = synchronized(retainedLock) {
        retained.toByteArray().toString(Charsets.UTF_8)
    }
}

private fun terminateProcess(process: Process) {
    val descendants = processDescendants(process)
    descendants.asReversed().forEach { destroyProcessHandle(it, forcibly = false) }
    runCatching { process.destroy() }
    val exitedAfterDestroy = runCatching {
        process.waitFor(ProcessDestroyGraceMillis, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)
    descendants.asReversed().forEach { destroyProcessHandle(it, forcibly = true) }
    if (!exitedAfterDestroy) {
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(ProcessForceDestroyGraceMillis, TimeUnit.MILLISECONDS) }
    }
}

/** ProcessHandle is absent or incomplete on some Android releases, so tree cleanup is reflective. */
private fun processDescendants(process: Process): List<Any> = runCatching {
    val handle = Process::class.java.getMethod("toHandle").invoke(process)
    val processHandleClass = Class.forName("java.lang.ProcessHandle")
    val stream = processHandleClass.getMethod("descendants").invoke(handle) as AutoCloseable
    try {
        val iterator = Class.forName("java.util.stream.BaseStream")
            .getMethod("iterator")
            .invoke(stream) as Iterator<*>
        buildList {
            while (iterator.hasNext()) iterator.next()?.let(::add)
        }
    } finally {
        stream.close()
    }
}.getOrDefault(emptyList())

private fun destroyProcessHandle(handle: Any, forcibly: Boolean) {
    runCatching {
        val methodName = if (forcibly) "destroyForcibly" else "destroy"
        Class.forName("java.lang.ProcessHandle").getMethod(methodName).invoke(handle)
    }
}

private fun closeProcessStreams(process: Process) {
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
}

private const val DefaultProcessReadBufferBytes = 8 * 1024
private const val ProcessDestroyGraceMillis = 250L
private const val ProcessForceDestroyGraceMillis = 250L
private const val ProcessReaderJoinMillis = 500L
private const val ProcessReaderCloseJoinMillis = 250L
