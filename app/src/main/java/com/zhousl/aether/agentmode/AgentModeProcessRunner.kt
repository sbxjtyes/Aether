package com.zhousl.aether.agentmode

import com.zhousl.aether.util.ProcessOutputResult
import com.zhousl.aether.util.awaitMergedProcessOutput

/**
 * Runs a short-lived command for the privileged Agent Mode service.
 *
 * Binder calls into the service are synchronous, so every child process must have a timeout that
 * expires before the caller-side Binder timeout. stderr is merged into stdout and continuously
 * drained by [awaitMergedProcessOutput], which also bounds retained output and terminates the
 * process when the deadline expires or the Binder thread is interrupted.
 */
internal fun runAgentModeProcess(
    command: List<String>,
    timeoutMillis: Long,
    maxOutputBytes: Int = AgentModeProcessOutputLimitBytes,
    processStarter: (List<String>) -> Process = { processCommand ->
        ProcessBuilder(processCommand)
            .redirectErrorStream(true)
            .start()
    },
): ProcessOutputResult {
    require(command.isNotEmpty()) { "Agent Mode process command must not be empty." }
    val process = processStarter(command)
    return awaitMergedProcessOutput(
        process = process,
        timeoutMillis = timeoutMillis,
        maxOutputBytes = maxOutputBytes,
    )
}

internal fun ProcessOutputResult.failureMessage(
    operation: String,
    timeoutMillis: Long,
): String {
    val detail = output.trim().ifBlank {
        if (timedOut) {
            "$operation timed out after $timeoutMillis ms."
        } else {
            "$operation failed with exit code $exitCode."
        }
    }
    val timeoutPrefix = if (timedOut && !detail.contains("timed out", ignoreCase = true)) {
        "$operation timed out after $timeoutMillis ms.\n"
    } else {
        ""
    }
    val truncationSuffix = if (truncated) "\n[output truncated]" else ""
    return timeoutPrefix + detail + truncationSuffix
}

internal const val AgentModeProcessOutputLimitBytes = 64 * 1024
