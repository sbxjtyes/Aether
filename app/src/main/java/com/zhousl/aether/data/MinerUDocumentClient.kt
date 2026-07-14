package com.zhousl.aether.data

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val DefaultAgentBaseUrl = "https://mineru.net/api/v1/agent"
private const val DefaultPreciseBaseUrl = "https://mineru.net/api/v4"
private const val MaxLightweightBytes = 10 * 1024 * 1024
// Workspace files currently have to be materialized in memory before signed upload.
// Keep a conservative app-side cap to avoid exhausting the Android heap.
private const val MaxPreciseBytes = 32 * 1024 * 1024
private const val MaxMarkdownChars = 120_000
private const val MaxResultZipBytes = 16 * 1024 * 1024

class MinerUDocumentClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).build(),
    private val agentBaseUrl: String = DefaultAgentBaseUrl,
    private val preciseBaseUrl: String = DefaultPreciseBaseUrl,
) {
    suspend fun parseWorkspaceFile(
        workspaceFileBridge: WorkspaceFileBridge, path: String, workingDirectory: String,
        language: String, pageRange: String, enableTable: Boolean, enableOcr: Boolean,
        enableFormula: Boolean, timeoutSeconds: Int, apiToken: String,
    ): String = withContext(Dispatchers.IO) {
        val limit = if (apiToken.isNotBlank()) MaxPreciseBytes else MaxLightweightBytes
        val payload = workspaceFileBridge.readWorkspaceFile(path, workingDirectory, limit + 1).getOrThrow()
        parsePayload(
            payload, language, pageRange, enableTable, enableOcr, enableFormula, timeoutSeconds, apiToken,
        )
    }

    internal suspend fun parsePayload(
        payload: WorkspaceFilePayload, language: String = "ch", pageRange: String = "",
        enableTable: Boolean = true, enableOcr: Boolean = false, enableFormula: Boolean = true,
        timeoutSeconds: Int = 300, apiToken: String = "",
    ): String {
        val fileName = payload.absolutePath.substringAfterLast('/')
        val normalizedPageRange = normalizeMinerUPageRange(pageRange)
        require(fileName.substringAfterLast('.', "").lowercase() in SupportedExtensions) {
            "MinerU supports PDF, images, DOC/DOCX, PPT/PPTX, and XLSX files."
        }
        return if (apiToken.isNotBlank()) {
            val overallDeadline = deadline(timeoutSeconds)
            val precise = runCatching {
                parsePrecisely(payload, fileName, apiToken, language, normalizedPageRange, enableTable, enableOcr, enableFormula, overallDeadline)
            }
            precise.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val remainingSeconds = ((overallDeadline - System.currentTimeMillis()) / 1000L).toInt()
            if (precise.isFailure && remainingSeconds < 15) {
                error(precise.exceptionOrNull()?.message ?: "MinerU precise parsing failed and no fallback time remains.")
            }
            precise.getOrNull() ?: parseLightweight(
                payload, fileName, language, normalizedPageRange, enableTable, enableOcr, enableFormula,
                remainingSeconds, precise.exceptionOrNull()?.message.orEmpty(),
            )
        } else {
            parseLightweight(payload, fileName, language, normalizedPageRange, enableTable, enableOcr, enableFormula, timeoutSeconds, "")
        }
    }

    private suspend fun parseLightweight(
        payload: WorkspaceFilePayload, fileName: String, language: String, pageRange: String,
        enableTable: Boolean, enableOcr: Boolean, enableFormula: Boolean, timeoutSeconds: Int,
        fallbackReason: String,
    ): String {
        require(payload.sizeBytes <= MaxLightweightBytes) {
            if (fallbackReason.isBlank()) "MinerU lightweight parsing accepts files up to 10 MB."
            else "Precise parsing failed ($fallbackReason), and the file exceeds the 10 MB fallback limit."
        }
        val body = options(fileName, language, pageRange, enableTable, enableOcr, enableFormula)
        val created = executeJson(Request.Builder().url("$agentBaseUrl/parse/file")
            .post(body.toString().toRequestBody(JsonMediaType)).build())
        ensureSuccess(created)
        val data = created.getJSONObject("data")
        val taskId = data.getString("task_id")
        upload(data.getString("file_url"), payload.bytes)
        val deadline = deadline(timeoutSeconds)
        while (System.currentTimeMillis() < deadline) {
            val item = executeJson(Request.Builder().url("$agentBaseUrl/parse/$taskId").get().build()).also(::ensureSuccess).getJSONObject("data")
            when (item.optString("state")) {
                "done" -> {
                    val markdownUrl = item.getString("markdown_url")
                    return output("lightweight", payload.absolutePath, executeText(Request.Builder().url(markdownUrl).get().build())).apply {
                        put("task_id", taskId); put("markdown_url", markdownUrl)
                        put("fell_back_from_precise", fallbackReason.isNotBlank())
                        if (fallbackReason.isNotBlank()) put("precise_error", fallbackReason.take(500))
                    }.toString()
                }
                "failed" -> error(item.optString("err_msg").ifBlank { "MinerU lightweight parsing failed." })
            }
            delay(3_000)
        }
        error("MinerU lightweight parsing timed out. Task id: $taskId")
    }

    private suspend fun parsePrecisely(
        payload: WorkspaceFilePayload, fileName: String, token: String, language: String,
        pageRange: String, enableTable: Boolean, enableOcr: Boolean, enableFormula: Boolean,
        pollingDeadline: Long,
    ): String {
        val file = JSONObject().put("name", fileName).put("is_ocr", enableOcr)
        if (pageRange.isNotBlank()) file.put("page_ranges", pageRange)
        val body = JSONObject().put("files", JSONArray().put(file)).put("model_version", "vlm")
            .put("language", language.ifBlank { "ch" }).put("enable_table", enableTable)
            .put("enable_formula", enableFormula)
        val created = executeJson(authorized("$preciseBaseUrl/file-urls/batch", token)
            .post(body.toString().toRequestBody(JsonMediaType)).build())
        ensureSuccess(created)
        val data = created.getJSONObject("data")
        val batchId = data.getString("batch_id")
        upload(data.getJSONArray("file_urls").getString(0), payload.bytes)
        while (System.currentTimeMillis() < pollingDeadline) {
            val result = executeJson(authorized("$preciseBaseUrl/extract-results/batch/$batchId", token).get().build()).also(::ensureSuccess)
            val item = result.getJSONObject("data").optJSONArray("extract_result")?.optJSONObject(0)
            when (item?.optString("state")) {
                "done" -> {
                    val zipUrl = item.getString("full_zip_url")
                    return output("precise", payload.absolutePath, extractMarkdown(executeBytes(Request.Builder().url(zipUrl).get().build(), MaxResultZipBytes))).apply {
                        put("batch_id", batchId); put("full_zip_url", zipUrl)
                    }.toString()
                }
                "failed" -> error(item.optString("err_msg").ifBlank { "MinerU precise parsing failed." })
            }
            delay(3_000)
        }
        error("MinerU precise parsing timed out. Batch id: $batchId")
    }

    private fun options(name: String, language: String, pages: String, table: Boolean, ocr: Boolean, formula: Boolean) =
        JSONObject().put("file_name", name).put("language", language.ifBlank { "ch" })
            .put("enable_table", table).put("is_ocr", ocr).put("enable_formula", formula)
            .apply { if (pages.isNotBlank()) put("page_range", pages) }

    private fun output(mode: String, path: String, markdown: String) = JSONObject().put("ok", true)
        .put("parser_mode", mode).put("source_path", path).put("truncated", markdown.length > MaxMarkdownChars)
        .put("markdown", markdown.take(MaxMarkdownChars))
        .put(
            "quality_notice",
            "Extracted Markdown may contain OCR, table, or layout errors. Verify critical commands, numbers, and quotations against the source document.",
        )
        .put(
            "source_notice",
            "When summarizing, attribute claims to the document and flag version-specific or potentially outdated technical guidance.",
        )

    private fun deadline(seconds: Int) = System.currentTimeMillis() + seconds.coerceIn(15, 600) * 1000L
    private fun authorized(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")
    private fun upload(url: String, bytes: ByteArray) { httpClient.newCall(Request.Builder().url(url).put(bytes.toRequestBody(null)).build()).execute().use { check(it.isSuccessful) { "MinerU upload failed with HTTP ${it.code}." } } }
    private fun executeJson(request: Request) = JSONObject(executeText(request))
    private fun executeText(request: Request) = httpClient.newCall(request).execute().use { val text = it.body?.string().orEmpty(); check(it.isSuccessful) { "MinerU HTTP ${it.code}: ${text.take(500)}" }; text }
    private fun executeBytes(request: Request, maxBytes: Int) = httpClient.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "MinerU download failed with HTTP ${response.code}." }
        val body = response.body ?: error("Empty MinerU download.")
        check(body.contentLength() <= maxBytes || body.contentLength() < 0L) { "MinerU result ZIP is larger than the safe $maxBytes-byte limit." }
        val bytes = body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                check(output.size() + read <= maxBytes) { "MinerU result ZIP exceeded the safe $maxBytes-byte limit." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        bytes
    }
    private fun ensureSuccess(json: JSONObject) { check(json.optInt("code", -1) == 0) { json.optString("msg").ifBlank { "MinerU API request failed." } } }
    private fun extractMarkdown(bytes: ByteArray): String { ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("full.md", true)) { val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (output.size() <= MaxMarkdownChars) { val read = zip.read(buffer, 0, minOf(buffer.size, MaxMarkdownChars + 1 - output.size())); if (read < 0) break; output.write(buffer, 0, read) }; return output.toByteArray().toString(Charsets.UTF_8) } } }; error("MinerU result ZIP did not contain full.md.") }

    companion object {
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val SupportedExtensions = setOf("pdf", "png", "jpg", "jpeg", "jp2", "webp", "gif", "bmp", "doc", "docx", "ppt", "pptx", "xlsx")
    }
}

internal fun normalizeMinerUPageRange(value: String): String {
    val normalized = value.trim().replace(" ", "")
    if (normalized.isBlank() || normalized == "1-") return ""
    require(normalized.matches(Regex("[1-9]\\d*(?:-[1-9]\\d*)?"))) {
        "page_range must be empty for the full document, a single page such as 5, or a closed range such as 1-10."
    }
    if ('-' in normalized) {
        val (start, end) = normalized.split('-', limit = 2).map(String::toLong)
        require(start <= end) { "page_range start page must not be greater than end page." }
    }
    return normalized
}
