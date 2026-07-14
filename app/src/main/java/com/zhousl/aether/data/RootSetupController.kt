package com.zhousl.aether.data

import android.content.Context
import com.zhousl.aether.termux.TermuxBashTool
import com.zhousl.aether.termux.TermuxContract
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val RootProbeTimeoutMillis = 1_500L
private const val RootSetupTimeoutMillis = 30_000L
private const val RootProcessTerminationGraceMillis = 250L
private const val RootProcessOutputJoinMillis = 1_000L
private const val RootProcessOutputLimitChars = 256 * 1024
private const val TermuxLaunchForBackgroundMarker = "AETHER_TERMUX_LAUNCHED_FOR_BACKGROUND"

enum class RootSetupIssue {
    Unknown,
    Available,
    Running,
    Ready,
    Unavailable,
    PermissionDenied,
    TermuxNotInstalled,
    Failed,
}

data class RootSetupState(
    val issue: RootSetupIssue = RootSetupIssue.Unknown,
    val detail: String = "",
    val rootAvailable: Boolean = false,
    val suPath: String = "",
    val didLaunchTermuxForBackground: Boolean = false,
    val lastUpdatedMillis: Long = 0L,
) {
    val isReady: Boolean
        get() = issue == RootSetupIssue.Ready

    val isRunning: Boolean
        get() = issue == RootSetupIssue.Running
}

class RootSetupController(
    private val context: Context,
    private val bashTool: TermuxBashTool,
) {
    suspend fun inspect(): RootSetupState = withContext(Dispatchers.IO) {
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            RootSetupState(
                issue = RootSetupIssue.Unavailable,
                detail = "No su binary was detected on this device.",
                rootAvailable = false,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        } else {
            RootSetupState(
                issue = RootSetupIssue.Available,
                detail = "Root appears to be available. Aether can request su to finish local setup automatically.",
                rootAvailable = true,
                suPath = suPath,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }
    }

    suspend fun configureLocalAccess(): RootSetupState = withContext(Dispatchers.IO) {
        val suPath = findSuPath()
        if (suPath.isBlank()) {
            return@withContext RootSetupState(
                issue = RootSetupIssue.Unavailable,
                detail = "No su binary was detected on this device.",
                rootAvailable = false,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }
        if (!isTermuxInstalled()) {
            return@withContext RootSetupState(
                issue = RootSetupIssue.TermuxNotInstalled,
                detail = "Install Termux before using root automatic setup.",
                rootAvailable = true,
                suPath = suPath,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }

        val commandResult = runRootProcess(
            command = listOf(suPath, "-c", buildTermuxRootSetupScript(context.packageName)),
            timeoutMillis = RootSetupTimeoutMillis,
        )
        if (commandResult.timedOut || commandResult.exitCode != 0) {
            val detail = commandResult.combinedOutput().ifBlank {
                if (commandResult.timedOut) {
                    "Root request timed out. Grant su to Aether, then try again."
                } else {
                    commandResult.launchError.ifBlank { "Root setup command failed." }
                }
            }
            return@withContext RootSetupState(
                issue = if (looksLikeRootDenied(detail) || commandResult.timedOut) {
                    RootSetupIssue.PermissionDenied
                } else {
                    RootSetupIssue.Failed
                },
                detail = detail.take(280),
                rootAvailable = true,
                suPath = suPath,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }

        val didLaunchTermuxForBackground =
            commandResult.stdout.contains(TermuxLaunchForBackgroundMarker)
        val termuxSetup = bashTool.inspectSetup()
        if (termuxSetup.isReady) {
            RootSetupState(
                issue = RootSetupIssue.Ready,
                detail = "Root setup completed. Termux command access and Agent Mode Root authorization are ready.",
                rootAvailable = true,
                suPath = suPath,
                didLaunchTermuxForBackground = didLaunchTermuxForBackground,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        } else {
            RootSetupState(
                issue = RootSetupIssue.Failed,
                detail = termuxSetup.detail.ifBlank {
                    "Root setup finished, but Termux still did not accept the setup probe."
                },
                rootAvailable = true,
                suPath = suPath,
                didLaunchTermuxForBackground = didLaunchTermuxForBackground,
                lastUpdatedMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TermuxContract.PackageName, 0)
        true
    }.getOrDefault(false)

    private suspend fun findSuPath(): String {
        val commonPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
        )
        commonPaths.firstOrNull { path ->
            File(path).let { it.exists() && it.canExecute() }
        }?.let { return it }

        val result = runRootProcess(
            command = listOf("sh", "-c", "command -v su 2>/dev/null || true"),
            timeoutMillis = RootProbeTimeoutMillis,
        )
        return result.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
    }

    private fun buildTermuxRootSetupScript(
        aetherPackageName: String,
    ): String = """
        set -u
        termux_pkg='${TermuxContract.PackageName}'
        aether_pkg='${escapeForSingleQuoted(aetherPackageName)}'
        run_command_permission='${TermuxContract.RunCommandPermission}'
        current_user="${'$'}(cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || printf '0')"
        current_user="${'$'}(printf '%s' "${'$'}current_user" | tr -cd '0-9')"
        current_user="${'$'}{current_user:-0}"
        termux_data="${'$'}(
          cmd package dump "${'$'}termux_pkg" 2>/dev/null |
            sed -n 's/^[[:space:]]*dataDir=//p' |
            grep -v '^null${'$'}' |
            head -n 1
        )"
        if [ -z "${'$'}termux_data" ] || [ "${'$'}termux_data" = "null" ]; then
          termux_data="/data/user/${'$'}current_user/${'$'}termux_pkg"
        fi
        termux_home="${'$'}termux_data/files/home"
        termux_files="${'$'}termux_data/files"
        termux_bash="${'$'}termux_data/files/usr/bin/bash"
        props_dir="${'$'}termux_home/.termux"
        props="${'$'}props_dir/termux.properties"
        did_launch_termux=false

        launch_app() {
          package_name="${'$'}1"
          am start --user "${'$'}current_user" \
            -a android.intent.action.MAIN \
            -c android.intent.category.LAUNCHER \
            -p "${'$'}package_name" >/dev/null 2>&1 && return 0

          resolved_activity="${'$'}(
            cmd package resolve-activity --brief "${'$'}package_name" 2>/dev/null |
              tail -n 1
          )"
          if [ -n "${'$'}resolved_activity" ] && printf '%s' "${'$'}resolved_activity" | grep -q '/'; then
            am start --user "${'$'}current_user" -n "${'$'}resolved_activity" >/dev/null 2>&1 && return 0
          fi

          monkey --user "${'$'}current_user" -p "${'$'}package_name" 1 >/dev/null 2>&1
        }

        is_package_running() {
          package_name="${'$'}1"
          pidof "${'$'}package_name" >/dev/null 2>&1 && return 0
          pgrep -f "^${'$'}package_name([:]|${'$'})" >/dev/null 2>&1 && return 0
          return 1
        }

        app_id="${'$'}(
          cmd package dump "${'$'}termux_pkg" 2>/dev/null |
            sed -n 's/.*userId=//p' |
            head -n 1 |
            sed 's/[^0-9].*//'
        )"
        owner=""
        if [ -n "${'$'}app_id" ]; then
          owner="${'$'}app_id:${'$'}app_id"
          if [ "${'$'}current_user" -gt 0 ] 2>/dev/null; then
            full_uid="${'$'}((current_user * 100000 + app_id))"
            owner="${'$'}full_uid:${'$'}full_uid"
          fi
        fi

        mkdir -p "${'$'}termux_home" || exit 20
        if [ -n "${'$'}owner" ]; then
          chown "${'$'}owner" "${'$'}termux_data" "${'$'}termux_files" "${'$'}termux_home" 2>/dev/null || true
        fi
        chmod 700 "${'$'}termux_home" 2>/dev/null || true

        should_launch_termux=false
        if ! is_package_running "${'$'}termux_pkg"; then
          should_launch_termux=true
        fi

        if [ ! -x "${'$'}termux_bash" ]; then
          launch_app "${'$'}termux_pkg" || true
          did_launch_termux=true
          launch_app "${'$'}aether_pkg" || true
          sleep 5
        fi

        mkdir -p "${'$'}props_dir" || exit 21
        touch "${'$'}props" || exit 22
        if grep -Eq '^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=' "${'$'}props"; then
          sed -i -E 's/^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps=true/' "${'$'}props" || exit 23
        else
          printf '\nallow-external-apps=true\n' >> "${'$'}props" || exit 24
        fi

        owner="${'$'}(stat -c '%u:%g' "${'$'}termux_home" 2>/dev/null || true)"
        if [ -n "${'$'}owner" ]; then
          chown "${'$'}owner" "${'$'}props_dir" "${'$'}props" 2>/dev/null || true
        fi
        chmod 700 "${'$'}props_dir" 2>/dev/null || true
        chmod 600 "${'$'}props" 2>/dev/null || true

        pm grant --user "${'$'}current_user" "${'$'}aether_pkg" "${'$'}run_command_permission" >/dev/null 2>&1 ||
          cmd package grant --user "${'$'}current_user" "${'$'}aether_pkg" "${'$'}run_command_permission" >/dev/null 2>&1 ||
          pm grant "${'$'}aether_pkg" "${'$'}run_command_permission" >/dev/null 2>&1 ||
          cmd package grant "${'$'}aether_pkg" "${'$'}run_command_permission" >/dev/null 2>&1 ||
          true
        am broadcast --user "${'$'}current_user" -a com.termux.app.reload_style -p "${'$'}termux_pkg" >/dev/null 2>&1 || true
        if [ "${'$'}should_launch_termux" = true ] && [ "${'$'}did_launch_termux" != true ]; then
          launch_app "${'$'}termux_pkg" || true
          did_launch_termux=true
        fi
        if [ "${'$'}did_launch_termux" = true ]; then
          echo $TermuxLaunchForBackgroundMarker
          launch_app "${'$'}aether_pkg" || true
        fi
        echo AETHER_ROOT_SETUP_READY
    """.trimIndent()

    private fun escapeForSingleQuoted(value: String): String =
        value.replace("'", "'\"'\"'")

    private fun looksLikeRootDenied(value: String): Boolean {
        val normalized = value.lowercase()
        return "denied" in normalized ||
            "permission" in normalized ||
            "not allowed" in normalized ||
            "su:" in normalized
    }
}

internal data class RootCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val launchError: String = "",
) {
    fun combinedOutput(): String = listOf(stdout, stderr, launchError)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\n")
}

/**
 * Runs the root helper while continuously draining both output pipes. Waiting before reading can
 * deadlock as soon as either OS pipe buffer fills. Cancellation deliberately remains distinct from
 * a timeout so callers never turn a cancelled setup into a permission error.
 */
internal suspend fun runRootProcess(
    command: List<String>,
    timeoutMillis: Long,
    processStarter: (List<String>) -> Process = { ProcessBuilder(it).start() },
): RootCommandResult = coroutineScope {
    val process = runCatching { processStarter(command) }.getOrElse { throwable ->
        return@coroutineScope RootCommandResult(
            exitCode = -1,
            launchError = throwable.message.orEmpty(),
        )
    }
    val stdoutReader = async(Dispatchers.IO) {
        readProcessOutput(process.inputStream)
    }
    val stderrReader = async(Dispatchers.IO) {
        readProcessOutput(process.errorStream)
    }

    var timedOut = false
    val exitCode = try {
        val completedExitCode = withTimeoutOrNull(timeoutMillis) {
            runInterruptible(Dispatchers.IO) { process.waitFor() }
        }
        if (completedExitCode == null) {
            timedOut = true
            terminateProcessTree(process)
            -1
        } else {
            completedExitCode
        }
    } catch (cancelled: CancellationException) {
        terminateProcessTree(process)
        throw cancelled
    } catch (throwable: Throwable) {
        terminateProcessTree(process)
        throw throwable
    } finally {
        if (!timedOut && process.isAlive) {
            terminateProcessTree(process)
        }
    }

    if (timedOut) {
        closeProcessStreams(process)
    }
    val stdout = withTimeoutOrNull(RootProcessOutputJoinMillis) { stdoutReader.await() }
        ?: run {
            runCatching { process.inputStream.close() }
            stdoutReader.cancel()
            ""
        }
    val stderr = withTimeoutOrNull(RootProcessOutputJoinMillis) { stderrReader.await() }
        ?: run {
            runCatching { process.errorStream.close() }
            stderrReader.cancel()
            ""
        }
    RootCommandResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        timedOut = timedOut,
    )
}

private fun readProcessOutput(input: InputStream): String = runCatching {
    input.bufferedReader().use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            output.append(buffer, 0, count)
            if (output.length > RootProcessOutputLimitChars) {
                output.delete(0, output.length - RootProcessOutputLimitChars)
            }
        }
        output.toString()
    }
}.getOrDefault("")

private fun terminateProcessTree(process: Process) {
    // ProcessHandle is not consistently implemented by Android vendors, so tree cleanup is
    // best-effort and reflective. The direct process is always terminated below.
    val descendants = runCatching {
        val handle = Process::class.java.getMethod("toHandle").invoke(process)
        val processHandleClass = Class.forName("java.lang.ProcessHandle")
        val stream = processHandleClass.getMethod("descendants").invoke(handle) as AutoCloseable
        val iterator = Class.forName("java.util.stream.BaseStream")
            .getMethod("iterator")
            .invoke(stream) as Iterator<*>
        buildList {
            while (iterator.hasNext()) iterator.next()?.let(::add)
        }.also { stream.close() }
    }.getOrDefault(emptyList())
    descendants.asReversed().forEach { destroyProcessHandle(it, forcibly = false) }
    runCatching { process.destroy() }
    val exited = runCatching {
        process.waitFor(RootProcessTerminationGraceMillis, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)
    descendants.asReversed().forEach { destroyProcessHandle(it, forcibly = true) }
    if (!exited) {
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(RootProcessTerminationGraceMillis, TimeUnit.MILLISECONDS) }
    }
    closeProcessStreams(process)
}

private fun destroyProcessHandle(handle: Any, forcibly: Boolean) {
    runCatching {
        val methodName = if (forcibly) "destroyForcibly" else "destroy"
        Class.forName("java.lang.ProcessHandle").getMethod(methodName).invoke(handle)
    }
}

private fun closeProcessStreams(process: Process) {
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
}
