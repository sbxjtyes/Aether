package com.zhousl.aether.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.zhousl.aether.termux.TermuxBashTool
import com.zhousl.aether.termux.TermuxContract
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MaxWorkspaceImportBytes = 128 * 1024 * 1024
private const val MaxWorkspaceDownloadBytes = 32 * 1024 * 1024
private const val WorkspaceTransferChunkBytes = 6 * 1024
private const val WorkspaceUploadChunkChars = 192 * 1024
private const val WorkspaceBaseDirectoryName = ".aether/workspaces"
private const val WorkspaceUploadTimeoutMillis = 60_000L
private const val WorkspaceUploadProbeTimeoutMillis = 10_000L
private const val WorkspaceUploadVerificationTimeoutMillis = 35_000L
private const val WorkspaceUploadVerificationIntervalMillis = 500L
private const val RootReadTimeoutMillis = 20_000L
private const val WorkspaceFileBridgeLogTag = "AetherWorkspaceFile"

class WorkspaceFileBridge(
    private val context: Context,
    private val bashTool: TermuxBashTool = TermuxBashTool(context),
) {
    private val workspaceUploadMutex = Mutex()

    fun workspaceDirectory(sessionId: String): String =
        "${TermuxContract.HomeDirectory}/$WorkspaceBaseDirectoryName/$sessionId"

    suspend fun deleteWorkspace(sessionId: String): Result<Unit> = runCatching {
        val workspacePath = workspaceDirectory(sessionId)
        val rawResult = JSONObject(
            bashTool.executeCommand(
                command = buildDeleteWorkspaceCommand(encodeBase64(workspacePath)),
                awaitTimeoutMillis = WorkspaceUploadProbeTimeoutMillis,
            )
        )
        ensureBashSuccess(
            rawResult = rawResult,
            fallbackMessage = "Couldn't delete workspace files for $sessionId.",
        )
    }

    suspend fun importAttachmentToWorkspace(
        sourceUri: Uri,
        sessionId: String,
        attachmentId: String,
        displayName: String,
    ): Result<ImportedWorkspaceFile> = runCatching {
        val safeFileName = buildWorkspaceFileName(
            attachmentId = attachmentId,
            displayName = displayName,
        )
        val destinationPath = "${workspaceDirectory(sessionId)}/uploads/$safeFileName"
        val bytes = readContentBytes(
            sourceUri = sourceUri,
            byteLimit = MaxWorkspaceImportBytes + 1,
        ).getOrElse { throwable ->
            error(
                throwable.message
                    ?.takeIf { it.isNotBlank() }
                    ?: "Couldn't read the selected file."
            )
        }

        if (bytes.size > MaxWorkspaceImportBytes) {
            error("File is larger than ${formatBytes(MaxWorkspaceImportBytes.toLong())}.")
        }

        val bytesCopied = writeWorkspaceBytes(
            absolutePath = destinationPath,
            bytes = bytes,
        ).getOrThrow()

        ImportedWorkspaceFile(
            absolutePath = destinationPath,
            bytesCopied = bytesCopied,
        )
    }

    suspend fun readWorkspaceFile(
        path: String,
        workingDirectory: String = TermuxContract.HomeDirectory,
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> = runCatching {
        require(byteLimit > 0) { "byteLimit must be greater than 0." }

        val absolutePath = resolveTermuxPath(
            path = path,
            workingDirectory = workingDirectory,
        )
        val output = ByteArrayOutputStream()
        var expectedSizeBytes: Long? = null
        var offsetBytes = 0L

        while (expectedSizeBytes == null || offsetBytes < expectedSizeBytes) {
            val remainingBudget = byteLimit.toLong() - offsetBytes
            if (remainingBudget <= 0L) {
                error("File is larger than ${formatBytes(byteLimit.toLong())}: $absolutePath")
            }

            val chunk = readWorkspaceFileChunk(
                absolutePath = absolutePath,
                offsetBytes = offsetBytes,
                byteLimit = minOf(
                    WorkspaceTransferChunkBytes,
                    remainingBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1),
                ),
                maxTotalBytes = byteLimit,
            ).getOrThrow()

            expectedSizeBytes = chunk.sizeBytes
            if (expectedSizeBytes == 0L) {
                break
            }
            if (chunk.bytes.isEmpty()) {
                error("Couldn't read file data from $absolutePath.")
            }

            output.write(chunk.bytes)
            offsetBytes += chunk.bytes.size
        }

        val sizeBytes = expectedSizeBytes ?: 0L
        val bytes = output.toByteArray()
        if (bytes.size.toLong() != sizeBytes) {
            error("Workspace read returned ${bytes.size} bytes, expected $sizeBytes.")
        }

        WorkspaceFilePayload(
            absolutePath = absolutePath,
            bytes = bytes,
            sizeBytes = sizeBytes,
        )
    }

    suspend fun readRootImageFile(
        path: String,
        workingDirectory: String = TermuxContract.HomeDirectory,
        byteLimit: Int,
    ): Result<WorkspaceFilePayload> = withContext(Dispatchers.IO) {
        runCatching {
            require(byteLimit > 0) { "byteLimit must be greater than 0." }
            val absolutePath = resolveTermuxPath(
                path = path,
                workingDirectory = workingDirectory,
            )
            val command = buildRootReadFileCommand(
                absolutePath = absolutePath,
                byteLimit = byteLimit,
            )
            val result = runRootCommand(command)
            if (result.exitCode != 0 || result.timedOut) {
                error(
                    result.combinedOutput().ifBlank {
                        if (result.timedOut) {
                            "Timed out reading image with root."
                        } else {
                            "Couldn't read image with root."
                        }
                    }
                )
            }
            val values = parseStructuredStdout(result.stdout)
            val sizeBytes = values["size_bytes"]?.toLongOrNull()
                ?: error("Root image read didn't report the file size.")
            val contentBase64 = values["content_b64"].orEmpty()
            val bytes = if (contentBase64.isBlank()) {
                ByteArray(0)
            } else {
                Base64.getDecoder().decode(contentBase64)
            }
            if (bytes.size.toLong() != sizeBytes) {
                error("Root image read returned ${bytes.size} bytes, expected $sizeBytes.")
            }
            WorkspaceFilePayload(
                absolutePath = absolutePath,
                bytes = bytes,
                sizeBytes = sizeBytes,
            )
        }
    }

    suspend fun saveWorkspaceFileToDocument(
        path: String,
        destinationUri: Uri,
        byteLimit: Int = MaxWorkspaceDownloadBytes,
    ): Boolean = runCatching {
        require(byteLimit > 0) { "byteLimit must be greater than 0." }

        val absolutePath = resolveTermuxPath(path = path)
        val resolver = context.contentResolver
        val didWrite = resolver.openOutputStream(destinationUri, "w")?.use { output ->
            var expectedSizeBytes: Long? = null
            var bytesWritten = 0L

            while (expectedSizeBytes == null || bytesWritten < expectedSizeBytes) {
                val remainingBudget = (byteLimit.toLong() - bytesWritten).coerceAtLeast(0L)
                val chunk = readWorkspaceFileChunk(
                    absolutePath = absolutePath,
                    offsetBytes = bytesWritten,
                    byteLimit = minOf(
                        WorkspaceTransferChunkBytes,
                        remainingBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1),
                    ),
                    maxTotalBytes = byteLimit,
                ).getOrThrow()

                expectedSizeBytes = chunk.sizeBytes
                if (expectedSizeBytes == 0L) {
                    break
                }
                if (chunk.bytes.isEmpty()) {
                    error("Couldn't read file data from $absolutePath.")
                }

                output.write(chunk.bytes)
                output.flush()
                bytesWritten += chunk.bytes.size
            }

            true
        } ?: false
        if (!didWrite) {
            runCatching { resolver.delete(destinationUri, null, null) }
        }
        didWrite
    }.getOrDefault(false)

    fun resolveWorkspaceDownloadName(rawLink: String): String {
        val normalizedPath = resolveLinkPath(rawLink)
        return normalizedPath.substringAfterLast('/').ifBlank { "download" }
    }

    fun resolveLinkPath(rawLink: String): String =
        resolveTermuxPath(
            path = normalizeFileLink(rawLink),
            workingDirectory = TermuxContract.HomeDirectory,
        )

    fun resolveTermuxPath(
        path: String,
        workingDirectory: String = TermuxContract.HomeDirectory,
    ): String {
        val trimmedPath = path.trim()
        val normalizedWorkingDirectory = normalizeHomePrefix(workingDirectory.trim())
            .ifBlank { TermuxContract.HomeDirectory }

        return when {
            trimmedPath.isBlank() -> normalizedWorkingDirectory
            trimmedPath == "~" -> TermuxContract.HomeDirectory
            trimmedPath.startsWith("~/") -> TermuxContract.HomeDirectory + trimmedPath.removePrefix("~")
            trimmedPath.startsWith("/") -> Paths.get(trimmedPath).normalize().toString()
            else -> Paths.get(normalizedWorkingDirectory).resolve(trimmedPath).normalize().toString()
        }
    }

    fun guessMimeType(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
    }

    suspend fun writeWorkspaceBytes(
        absolutePath: String,
        bytes: ByteArray,
    ): Result<Long> = workspaceUploadMutex.withLock {
        runCatching {
            val startedAtMillis = System.currentTimeMillis()
            val pathBase64 = encodeBase64(absolutePath)
            val expectedBytes = bytes.size.toLong()
            Log.i(
                WorkspaceFileBridgeLogTag,
                "upload start target=$absolutePath source_bytes=$expectedBytes",
            )

            executeUploadCommand(
                command = buildInitWorkspaceUploadCommand(pathBase64),
                stage = "init",
                absolutePath = absolutePath,
                fallbackMessage = "Couldn't prepare $absolutePath in the workspace.",
                verifier = { status ->
                    status.tmpBase64Exists && status.tmpBase64SizeBytes == 0L
                },
            )

            val contentBase64 = Base64.getEncoder().encodeToString(bytes)
            var expectedTempBase64Chars = 0L
            contentBase64.chunked(WorkspaceUploadChunkChars).forEachIndexed { index, chunk ->
                expectedTempBase64Chars += chunk.length.toLong()
                executeUploadCommand(
                    command = buildAppendWorkspaceUploadChunkCommand(
                        pathBase64 = pathBase64,
                        chunk = chunk,
                    ),
                    stage = "append[$index]",
                    absolutePath = absolutePath,
                    fallbackMessage = "Couldn't append a file chunk for $absolutePath in the workspace.",
                    verifier = { status ->
                        status.tmpBase64Exists && status.tmpBase64SizeBytes >= expectedTempBase64Chars
                    },
                )
            }

            val rawResult = executeUploadCommand(
                command = buildFinalizeWorkspaceUploadCommand(pathBase64),
                stage = "finalize",
                absolutePath = absolutePath,
                fallbackMessage = "Couldn't finalize $absolutePath in the workspace.",
                verifier = { status ->
                    status.destinationExists && status.destinationIsFile && status.destinationSizeBytes == expectedBytes
                },
            )
            val values = parseStructuredStdout(rawResult.optString("stdout"))
            val bytesWritten = values["bytes_written"]?.toLongOrNull() ?: expectedBytes
            if (bytesWritten != expectedBytes) {
                val finalStatus = waitForUploadStatus(
                    absolutePath = absolutePath,
                    timeoutMillis = WorkspaceUploadVerificationTimeoutMillis,
                ) { status ->
                    status.destinationExists && status.destinationIsFile && status.destinationSizeBytes == expectedBytes
                } ?: readUploadStatus(absolutePath).getOrNull()
                error(
                    "Workspace upload size check failed for $absolutePath: " +
                        "expected=$expectedBytes actual=${finalStatus?.destinationSizeBytes ?: bytesWritten} exists=${finalStatus?.destinationExists ?: false}"
                )
            }
            Log.i(
                WorkspaceFileBridgeLogTag,
                "upload success target=$absolutePath source_bytes=$expectedBytes bytes_written=$bytesWritten " +
                    "elapsed_ms=${System.currentTimeMillis() - startedAtMillis}",
            )
            bytesWritten
        }
    }

    private suspend fun executeUploadCommand(
        command: String,
        stage: String,
        absolutePath: String,
        fallbackMessage: String,
        verifier: suspend (WorkspaceUploadStatus) -> Boolean,
        awaitTimeoutMillis: Long = WorkspaceUploadTimeoutMillis,
    ): JSONObject {
        val startedAtMillis = System.currentTimeMillis()
        val rawResult = JSONObject(
            bashTool.executeCommand(
                command = command,
                awaitTimeoutMillis = awaitTimeoutMillis,
            )
        )
        val ok = rawResult.optBoolean("ok")
        val message = rawResult.optString("errmsg")
        val timedOut = message.contains("Timed out waiting for Termux", ignoreCase = true)
        Log.d(
            WorkspaceFileBridgeLogTag,
            "upload command result stage=$stage target=$absolutePath ok=$ok exit=${rawResult.optInt("exit_code", -1)} " +
                "err=${rawResult.optInt("err", -1)} timed_out=$timedOut duration_ms=${rawResult.optLong("duration_ms", -1)} " +
                "elapsed_ms=${System.currentTimeMillis() - startedAtMillis}",
        )
        if (ok) return rawResult

        if (timedOut) {
            val verifiedStatus = waitForUploadStatus(
                absolutePath = absolutePath,
                timeoutMillis = WorkspaceUploadVerificationTimeoutMillis,
                predicate = verifier,
            )
            Log.w(
                WorkspaceFileBridgeLogTag,
                "upload timeout compensation stage=$stage target=$absolutePath verified=${verifiedStatus != null} " +
                    "status=${verifiedStatus?.toLogString().orEmpty()} elapsed_ms=${System.currentTimeMillis() - startedAtMillis}",
            )
            if (verifiedStatus != null) {
                return rawResult.apply {
                    put("ok", true)
                    put("aether_timeout_compensated", true)
                    put("stdout", optString("stdout").ifBlank { "stage=$stage\ntimeout_compensated=true\n" })
                    put("errmsg", "")
                }
            }
        }

        ensureBashSuccess(
            rawResult = rawResult,
            fallbackMessage = fallbackMessage,
        )
        return rawResult
    }

    private suspend fun waitForUploadStatus(
        absolutePath: String,
        timeoutMillis: Long,
        predicate: suspend (WorkspaceUploadStatus) -> Boolean,
    ): WorkspaceUploadStatus? {
        val startedAtMillis = System.currentTimeMillis()
        var lastStatus: WorkspaceUploadStatus? = null
        while (System.currentTimeMillis() - startedAtMillis <= timeoutMillis) {
            val status = readUploadStatus(absolutePath).getOrNull()
            if (status != null) {
                lastStatus = status
                if (predicate(status)) {
                    Log.d(
                        WorkspaceFileBridgeLogTag,
                        "upload verify matched target=$absolutePath status=${status.toLogString()} " +
                            "elapsed_ms=${System.currentTimeMillis() - startedAtMillis}",
                    )
                    return status
                }
            }
            delay(WorkspaceUploadVerificationIntervalMillis)
        }
        Log.w(
            WorkspaceFileBridgeLogTag,
            "upload verify timeout target=$absolutePath last_status=${lastStatus?.toLogString().orEmpty()} " +
                "elapsed_ms=${System.currentTimeMillis() - startedAtMillis}",
        )
        return null
    }

    private suspend fun readUploadStatus(
        absolutePath: String,
    ): Result<WorkspaceUploadStatus> = runCatching {
        val rawResult = JSONObject(
            bashTool.executeCommand(
                command = buildWorkspaceUploadStatusCommand(encodeBase64(absolutePath)),
                awaitTimeoutMillis = WorkspaceUploadProbeTimeoutMillis,
            )
        )
        ensureBashSuccess(
            rawResult = rawResult,
            fallbackMessage = "Couldn't verify workspace upload status for $absolutePath.",
        )
        val values = parseStructuredStdout(rawResult.optString("stdout"))
        WorkspaceUploadStatus(
            destinationExists = values["dest_exists"].toBoolean(),
            destinationIsFile = values["dest_is_file"].toBoolean(),
            destinationSizeBytes = values["dest_size_bytes"]?.toLongOrNull() ?: -1L,
            tmpBase64Exists = values["tmp_b64_exists"].toBoolean(),
            tmpBase64SizeBytes = values["tmp_b64_size_bytes"]?.toLongOrNull() ?: -1L,
            tmpPathExists = values["tmp_path_exists"].toBoolean(),
            tmpPathSizeBytes = values["tmp_path_size_bytes"]?.toLongOrNull() ?: -1L,
            probeDurationMillis = rawResult.optLong("duration_ms", -1L),
        ).also { status ->
            Log.d(
                WorkspaceFileBridgeLogTag,
                "upload status target=$absolutePath ${status.toLogString()}",
            )
        }
    }.onFailure { throwable ->
        Log.w(
            WorkspaceFileBridgeLogTag,
            "upload status probe failed target=$absolutePath message=${throwable.message.orEmpty()}",
            throwable,
        )
    }

    private fun buildWorkspaceFileName(
        attachmentId: String,
        displayName: String,
    ): String {
        val trimmedName = displayName.trim().ifBlank { "attachment" }
        val dotIndex = trimmedName.lastIndexOf('.')
        val stem = if (dotIndex > 0) trimmedName.substring(0, dotIndex) else trimmedName
        val extension = if (dotIndex > 0 && dotIndex < trimmedName.lastIndex) {
            trimmedName.substring(dotIndex)
        } else {
            ""
        }
        val safeStem = sanitizePathSegment(stem).ifBlank { "attachment" }
        val safeExtension = sanitizeExtension(extension)
        val suffix = attachmentId.substringAfter("attachment-", attachmentId)
            .replace(Regex("[^a-zA-Z0-9_-]"), "")
            .takeLast(12)
            .ifBlank { System.currentTimeMillis().toString() }
        return "${safeStem}_$suffix$safeExtension"
    }

    private fun sanitizePathSegment(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)

    private fun sanitizeExtension(value: String): String {
        if (!value.startsWith('.')) return ""
        val sanitized = value.drop(1).replace(Regex("[^a-zA-Z0-9]"), "").take(12)
        return if (sanitized.isBlank()) "" else ".$sanitized"
    }

    private fun normalizeFileLink(rawLink: String): String {
        val trimmed = rawLink.trim()
        if (trimmed.isBlank()) return ""

        val withoutScheme = if (trimmed.startsWith("file://", ignoreCase = true)) {
            trimmed.substring("file://".length)
        } else {
            trimmed
        }
        val withoutLocalhost = when {
            withoutScheme.startsWith("localhost/") -> "/" + withoutScheme.removePrefix("localhost/")
            withoutScheme == "localhost" -> "/"
            else -> withoutScheme
        }
        val decodedPath = URLDecoder.decode(withoutLocalhost, Charsets.UTF_8.name()).trim()
        return decodedPath.replace(Regex(":(\\d+)$"), "")
    }

    private fun normalizeHomePrefix(path: String): String = when {
        path == "~" -> TermuxContract.HomeDirectory
        path.startsWith("~/") -> TermuxContract.HomeDirectory + path.removePrefix("~")
        else -> path
    }

    private fun parseStructuredStdout(stdout: String): Map<String, String> = buildMap {
        stdout.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) return@forEach
                put(
                    line.substring(0, separatorIndex),
                    line.substring(separatorIndex + 1),
                )
            }
    }

    private fun ensureBashSuccess(
        rawResult: JSONObject,
        fallbackMessage: String,
    ) {
        if (rawResult.optBoolean("ok")) return

        error(
            rawResult.optString("errmsg")
                .ifBlank { rawResult.optString("stderr") }
                .ifBlank { rawResult.optString("hint") }
                .ifBlank { fallbackMessage }
        )
    }

    private fun buildReadWorkspaceFileCommand(
        absolutePath: String,
        byteLimit: Int,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(absolutePath)}')\"")
        appendLine("byte_limit=$byteLimit")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'File not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ ! -f \"\$path\" ]; then")
        appendLine("  printf 'Path is not a file: %s\\n' \"\$path\" >&2")
        appendLine("  exit 21")
        appendLine("fi")
        appendLine("size_bytes=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("if [ \"\$size_bytes\" -gt \"\$byte_limit\" ]; then")
        appendLine("  printf 'File is larger than %s bytes: %s\\n' \"\$byte_limit\" \"\$path\" >&2")
        appendLine("  exit 22")
        appendLine("fi")
        appendLine("content_b64=\$(base64 < \"\$path\" | tr -d '\\n')")
        appendLine("emit_kv size_bytes \"\$size_bytes\"")
        appendLine("emit_kv content_b64 \"\$content_b64\"")
    }

    private fun buildRootReadFileCommand(
        absolutePath: String,
        byteLimit: Int,
    ): String = buildString {
        appendRootShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(absolutePath)}')\"")
        appendLine("byte_limit=$byteLimit")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'File not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ ! -f \"\$path\" ]; then")
        appendLine("  printf 'Path is not a file: %s\\n' \"\$path\" >&2")
        appendLine("  exit 21")
        appendLine("fi")
        appendLine("size_bytes=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("if [ \"\$size_bytes\" -gt \"\$byte_limit\" ]; then")
        appendLine("  printf 'Image is larger than %s bytes: %s\\n' \"\$byte_limit\" \"\$path\" >&2")
        appendLine("  exit 22")
        appendLine("fi")
        appendLine("content_b64=\$(base64 < \"\$path\" | tr -d '\\n')")
        appendLine("emit_kv size_bytes \"\$size_bytes\"")
        appendLine("emit_kv content_b64 \"\$content_b64\"")
    }

    private fun runRootCommand(
        command: String,
    ): RootReadCommandResult {
        val process = runCatching {
            ProcessBuilder("su", "-c", command).start()
        }.getOrElse { throwable ->
            return RootReadCommandResult(
                exitCode = -1,
                launchError = throwable.message.orEmpty(),
            )
        }
        var stdout = ""
        var stderr = ""
        val stdoutThread = thread(start = true, name = "aether-root-read-stdout") {
            stdout = runCatching { process.inputStream.bufferedReader().readText() }
                .getOrDefault("")
        }
        val stderrThread = thread(start = true, name = "aether-root-read-stderr") {
            stderr = runCatching { process.errorStream.bufferedReader().readText() }
                .getOrDefault("")
        }
        val finished = process.waitFor(RootReadTimeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroyForcibly() }
            }
        }
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
        return RootReadCommandResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = stdout,
            stderr = stderr,
            timedOut = !finished,
        )
    }

    private suspend fun readWorkspaceFileChunk(
        absolutePath: String,
        offsetBytes: Long,
        byteLimit: Int,
        maxTotalBytes: Int,
    ): Result<WorkspaceFilePayload> = runCatching {
        require(offsetBytes >= 0L) { "offsetBytes must not be negative." }
        require(byteLimit > 0) { "byteLimit must be greater than 0." }
        require(maxTotalBytes > 0) { "maxTotalBytes must be greater than 0." }

        val command = buildReadWorkspaceFileChunkCommand(
            absolutePath = absolutePath,
            offsetBytes = offsetBytes,
            byteLimit = byteLimit,
            maxTotalBytes = maxTotalBytes,
        )
        val rawResult = JSONObject(bashTool.executeCommand(command))
        ensureBashSuccess(
            rawResult = rawResult,
            fallbackMessage = "Couldn't read $absolutePath from the workspace.",
        )

        val values = parseStructuredStdout(rawResult.optString("stdout"))
        val sizeBytes = values["size_bytes"]?.toLongOrNull()
            ?: error(
                "Workspace read didn't report the file size. " +
                    "stdout_bytes=${rawResult.optString("stdout").toByteArray(Charsets.UTF_8).size}; " +
                    "stderr=${rawResult.optString("stderr").take(160)}"
            )
        val chunkSize = values["chunk_size"]?.toIntOrNull() ?: 0
        val contentBase64 = values["content_b64"].orEmpty()
        val bytes = if (contentBase64.isBlank()) {
            ByteArray(0)
        } else {
            Base64.getDecoder().decode(contentBase64)
        }

        if (chunkSize > 0 && bytes.isEmpty()) {
            error("Workspace read returned an empty data chunk.")
        }
        if (chunkSize != bytes.size) {
            error("Workspace read returned ${bytes.size} bytes, expected $chunkSize.")
        }

        WorkspaceFilePayload(
            absolutePath = absolutePath,
            bytes = bytes,
            sizeBytes = sizeBytes,
        )
    }

    private fun buildReadWorkspaceFileChunkCommand(
        absolutePath: String,
        offsetBytes: Long,
        byteLimit: Int,
        maxTotalBytes: Int,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '${encodeBase64(absolutePath)}')\"")
        appendLine("offset_bytes=$offsetBytes")
        appendLine("chunk_limit=$byteLimit")
        appendLine("max_total_bytes=$maxTotalBytes")
        appendLine("if [ ! -e \"\$path\" ]; then")
        appendLine("  printf 'File not found: %s\\n' \"\$path\" >&2")
        appendLine("  exit 20")
        appendLine("fi")
        appendLine("if [ ! -f \"\$path\" ]; then")
        appendLine("  printf 'Path is not a file: %s\\n' \"\$path\" >&2")
        appendLine("  exit 21")
        appendLine("fi")
        appendLine("size_bytes=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("if [ \"\$size_bytes\" -gt \"\$max_total_bytes\" ]; then")
        appendLine("  printf 'File is larger than %s bytes: %s\\n' \"\$max_total_bytes\" \"\$path\" >&2")
        appendLine("  exit 22")
        appendLine("fi")
        appendLine("chunk_size=0")
        appendLine("content_b64=''")
        appendLine("if [ \"\$offset_bytes\" -lt \"\$size_bytes\" ]; then")
        appendLine("  remaining=\$((size_bytes - offset_bytes))")
        appendLine("  chunk_size=\$chunk_limit")
        appendLine("  if [ \"\$remaining\" -lt \"\$chunk_size\" ]; then")
        appendLine("    chunk_size=\$remaining")
        appendLine("  fi")
        appendLine("  content_b64=\$( (tail -c \"+\$((offset_bytes + 1))\" -- \"\$path\" | head -c \"\$chunk_size\" || true) | base64 | tr -d '\\n')")
        appendLine("fi")
        appendLine("emit_kv content_b64 \"\$content_b64\"")
        appendLine("emit_kv size_bytes \"\$size_bytes\"")
        appendLine("emit_kv chunk_size \"\$chunk_size\"")
    }

    private fun buildInitWorkspaceUploadCommand(
        pathBase64: String,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '$pathBase64')\"")
        appendLine("parent_dir=\$(dirname -- \"\$path\")")
        appendLine("mkdir -p \"\$parent_dir\"")
        appendLine("tmp_b64=\"\${path}.aether-upload.b64\"")
        appendLine("tmp_path=\"\${path}.aether-tmp\"")
        appendLine("rm -f \"\$tmp_b64\" \"\$tmp_path\"")
        appendLine(": > \"\$tmp_b64\"")
        appendLine("emit_kv stage initialized")
    }

    private fun buildAppendWorkspaceUploadChunkCommand(
        pathBase64: String,
        chunk: String,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '$pathBase64')\"")
        appendLine("tmp_b64=\"\${path}.aether-upload.b64\"")
        appendLine("printf '%s' '$chunk' >> \"\$tmp_b64\"")
        appendLine("emit_kv stage appended")
    }

    private fun buildWorkspaceUploadStatusCommand(
        pathBase64: String,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '$pathBase64')\"")
        appendLine("tmp_b64=\"\${path}.aether-upload.b64\"")
        appendLine("tmp_path=\"\${path}.aether-tmp\"")
        appendLine("dest_exists=false")
        appendLine("dest_is_file=false")
        appendLine("dest_size_bytes=-1")
        appendLine("tmp_b64_exists=false")
        appendLine("tmp_b64_size_bytes=-1")
        appendLine("tmp_path_exists=false")
        appendLine("tmp_path_size_bytes=-1")
        appendLine("if [ -e \"\$path\" ]; then")
        appendLine("  dest_exists=true")
        appendLine("fi")
        appendLine("if [ -f \"\$path\" ]; then")
        appendLine("  dest_is_file=true")
        appendLine("  dest_size_bytes=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("fi")
        appendLine("if [ -e \"\$tmp_b64\" ]; then")
        appendLine("  tmp_b64_exists=true")
        appendLine("fi")
        appendLine("if [ -f \"\$tmp_b64\" ]; then")
        appendLine("  tmp_b64_size_bytes=\$(wc -c < \"\$tmp_b64\" | tr -d '[:space:]')")
        appendLine("fi")
        appendLine("if [ -e \"\$tmp_path\" ]; then")
        appendLine("  tmp_path_exists=true")
        appendLine("fi")
        appendLine("if [ -f \"\$tmp_path\" ]; then")
        appendLine("  tmp_path_size_bytes=\$(wc -c < \"\$tmp_path\" | tr -d '[:space:]')")
        appendLine("fi")
        appendLine("emit_kv dest_exists \"\$dest_exists\"")
        appendLine("emit_kv dest_is_file \"\$dest_is_file\"")
        appendLine("emit_kv dest_size_bytes \"\$dest_size_bytes\"")
        appendLine("emit_kv tmp_b64_exists \"\$tmp_b64_exists\"")
        appendLine("emit_kv tmp_b64_size_bytes \"\$tmp_b64_size_bytes\"")
        appendLine("emit_kv tmp_path_exists \"\$tmp_path_exists\"")
        appendLine("emit_kv tmp_path_size_bytes \"\$tmp_path_size_bytes\"")
    }

    private fun buildFinalizeWorkspaceUploadCommand(
        pathBase64: String,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '$pathBase64')\"")
        appendLine("tmp_b64=\"\${path}.aether-upload.b64\"")
        appendLine("tmp_path=\"\${path}.aether-tmp\"")
        appendLine("trap 'rm -f \"\$tmp_path\" \"\$tmp_b64\"' EXIT")
        appendLine("base64 -d < \"\$tmp_b64\" > \"\$tmp_path\"")
        appendLine("mv \"\$tmp_path\" \"\$path\"")
        appendLine("bytes_written=\$(wc -c < \"\$path\" | tr -d '[:space:]')")
        appendLine("rm -f \"\$tmp_b64\"")
        appendLine("trap - EXIT")
        appendLine("emit_kv bytes_written \"\$bytes_written\"")
    }

    private fun buildDeleteWorkspaceCommand(
        pathBase64: String,
    ): String = buildString {
        appendCommonShellPreamble(this)
        appendLine("path=\"\$(decode_b64 '$pathBase64')\"")
        appendLine("case \"\$path\" in")
        appendLine("  \"${TermuxContract.HomeDirectory}/$WorkspaceBaseDirectoryName\"/*) ;;")
        appendLine("  *) printf 'Refusing to delete unexpected workspace path: %s\\n' \"\$path\" >&2; exit 64 ;;")
        appendLine("esac")
        appendLine("rm -rf -- \"\$path\"")
        appendLine("emit_kv deleted \"\$path\"")
    }

    private fun readContentBytes(
        sourceUri: Uri,
        byteLimit: Int,
    ): Result<ByteArray> = runCatching {
        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalRead = 0

            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) break

                val remaining = byteLimit - totalRead
                if (remaining <= 0) break

                val writeCount = minOf(read, remaining)
                output.write(buffer, 0, writeCount)
                totalRead += writeCount

                if (totalRead >= byteLimit) break
            }

            output.toByteArray()
        } ?: error("Couldn't open the selected file. The document provider did not return a readable stream.")
    }

    private fun encodeBase64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun appendCommonShellPreamble(builder: StringBuilder) {
        builder.appendLine("set -euo pipefail")
        builder.appendLine("decode_b64() {")
        builder.appendLine("  printf '%s' \"\$1\" | base64 -d")
        builder.appendLine("}")
        builder.appendLine("emit_kv() {")
        builder.appendLine("  printf '%s=%s\\n' \"\$1\" \"\$2\"")
        builder.appendLine("}")
    }

    private fun appendRootShellPreamble(builder: StringBuilder) {
        builder.appendLine("set -eu")
        builder.appendLine("decode_b64() {")
        builder.appendLine("  printf '%s' \"\$1\" | base64 -d")
        builder.appendLine("}")
        builder.appendLine("emit_kv() {")
        builder.appendLine("  printf '%s=%s\\n' \"\$1\" \"\$2\"")
        builder.appendLine("}")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}

private data class RootReadCommandResult(
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

private data class WorkspaceUploadStatus(
    val destinationExists: Boolean,
    val destinationIsFile: Boolean,
    val destinationSizeBytes: Long,
    val tmpBase64Exists: Boolean,
    val tmpBase64SizeBytes: Long,
    val tmpPathExists: Boolean,
    val tmpPathSizeBytes: Long,
    val probeDurationMillis: Long,
) {
    fun toLogString(): String =
        "dest_exists=$destinationExists dest_is_file=$destinationIsFile dest_size=$destinationSizeBytes " +
            "tmp_b64_exists=$tmpBase64Exists tmp_b64_size=$tmpBase64SizeBytes " +
            "tmp_path_exists=$tmpPathExists tmp_path_size=$tmpPathSizeBytes probe_duration_ms=$probeDurationMillis"
}

data class ImportedWorkspaceFile(
    val absolutePath: String,
    val bytesCopied: Long,
)

data class WorkspaceFilePayload(
    val absolutePath: String,
    val bytes: ByteArray,
    val sizeBytes: Long,
)
