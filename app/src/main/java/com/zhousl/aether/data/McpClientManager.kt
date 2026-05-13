package com.zhousl.aether.data

import com.zhousl.aether.termux.TermuxBashTool
import com.zhousl.aether.termux.TermuxContract
import com.zhousl.aether.util.AetherLog
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val DefaultMcpProtocolVersion = "2025-11-25"
private const val McpRequestPollIntervalMillis = 150L
private const val McpDefaultRequestTimeoutMillis = 60_000L
private const val McpValidationTimeoutMillis = 20_000L
private const val McpLogTag = "AetherMcp"
private const val EnableMcpLogging = false

enum class McpConnectionStatus {
    Disconnected,
    Connecting,
    Ready,
    Error,
}

data class McpValidationSummary(
    val serverInfo: String = "",
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val promptCount: Int = 0,
)

data class McpToolBinding(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val description: String,
    val inputSchema: JSONObject,
) {
    val namespacedToolName: String
        get() = "mcp__${serverId}__${toolName}"

    val legacyToolName: String
        get() = "${serverId}:${toolName}"

    fun matchesToolCallName(callName: String): Boolean {
        val normalizedCallName = callName.trim()
        return namespacedToolName.equals(normalizedCallName, ignoreCase = true) ||
            legacyToolName.equals(normalizedCallName, ignoreCase = true) ||
            "${serverName}:${toolName}".equals(normalizedCallName, ignoreCase = true)
    }
}

data class McpResourceItem(
    val serverId: String,
    val serverName: String,
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
)

data class McpPromptItem(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val arguments: JSONArray,
)

data class McpLogEvent(
    val serverId: String,
    val level: String,
    val logger: String,
    val data: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)

data class McpTaskState(
    val serverId: String,
    val taskId: String,
    val status: String,
    val title: String = "",
    val detail: String = "",
)

data class McpServerSnapshot(
    val config: McpServerConfig,
    val status: McpConnectionStatus = McpConnectionStatus.Disconnected,
    val protocolVersion: String = "",
    val serverInfo: String = "",
    val tools: List<McpToolBinding> = emptyList(),
    val resources: List<McpResourceItem> = emptyList(),
    val prompts: List<McpPromptItem> = emptyList(),
    val logs: List<McpLogEvent> = emptyList(),
    val tasks: List<McpTaskState> = emptyList(),
    val errorMessage: String = "",
)

interface McpClientCallbacks {
    suspend fun listRoots(workspaceDirectory: String): List<String>

    suspend fun handleSamplingRequest(
        serverId: String,
        request: JSONObject,
    ): JSONObject?

    suspend fun handleElicitationRequest(
        serverId: String,
        request: JSONObject,
    ): JSONObject?
}

class DenyingMcpClientCallbacks : McpClientCallbacks {
    override suspend fun listRoots(workspaceDirectory: String): List<String> =
        listOf(workspaceDirectory)

    override suspend fun handleSamplingRequest(
        serverId: String,
        request: JSONObject,
    ): JSONObject? = null

    override suspend fun handleElicitationRequest(
        serverId: String,
        request: JSONObject,
    ): JSONObject? = null
}

class McpClientManager(
    private val bashTool: TermuxBashTool,
    private val callbacks: McpClientCallbacks = DenyingMcpClientCallbacks(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val sessions = ConcurrentHashMap<String, McpServerSession>()

    suspend fun syncServers(
        servers: List<McpServerConfig>,
        workspaceDirectory: String,
    ) = withContext(Dispatchers.IO) {
        val enabledIds = servers
            .filter { it.isEnabled }
            .map { it.id }
            .toSet()
        val obsoleteIds = sessions.keys.filterNot(enabledIds::contains)
        obsoleteIds.forEach { serverId -> disconnect(serverId) }

        servers
            .filter { it.isEnabled }
            .forEach { server ->
                val existing = sessions[server.id]
                if (existing == null || existing.config != server || existing.workspaceDirectory != workspaceDirectory) {
                    disconnect(server.id)
                    val session = McpServerSession(
                        config = server,
                        transport = createTransport(server, workspaceDirectory),
                        workspaceDirectory = workspaceDirectory,
                        callbacks = callbacks,
                    )
                    sessions[server.id] = session
                    session.connectAndRefresh()
                }
            }
    }

    suspend fun disconnect(serverId: String) = withContext(Dispatchers.IO) {
        sessions.remove(serverId)?.close()
    }

    suspend fun validateServer(
        server: McpServerConfig,
        workspaceDirectory: String,
    ): Result<McpValidationSummary> = withContext(Dispatchers.IO) {
        runCatching {
            validateMcpServerConfig(server)
            val session = McpServerSession(
                config = server,
                transport = createTransport(server, workspaceDirectory),
                workspaceDirectory = workspaceDirectory,
                callbacks = callbacks,
            )
            try {
                withTimeout(server.connectTimeoutMillis.coerceAtLeast(1_000L).coerceAtMost(McpValidationTimeoutMillis)) {
                    session.connectAndRefresh()
                }
                val snapshot = session.snapshot
                if (snapshot.status != McpConnectionStatus.Ready) {
                    error(snapshot.errorMessage.ifBlank { "MCP server did not become ready." })
                }
                McpValidationSummary(
                    serverInfo = snapshot.serverInfo,
                    toolCount = snapshot.tools.size,
                    resourceCount = snapshot.resources.size,
                    promptCount = snapshot.prompts.size,
                )
            } catch (throwable: TimeoutCancellationException) {
                error("Connection timed out while waiting for MCP server response.")
            } finally {
                session.close()
            }
        }
    }

    suspend fun refreshServer(serverId: String) = withContext(Dispatchers.IO) {
        sessions[serverId]?.refreshCatalog()
    }

    fun snapshots(): List<McpServerSnapshot> =
        sessions.values.map { it.snapshot }.sortedBy { it.config.displayName.lowercase() }

    fun toolBindings(): List<McpToolBinding> =
        sessions.values
            .flatMap { it.snapshot.tools }
            .sortedWith(compareBy({ it.serverName.lowercase() }, { it.toolName.lowercase() }))

    suspend fun listTools(
        serverId: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tools = if (serverId.isNullOrBlank()) {
                toolBindings()
            } else {
                val resolvedServerId = resolveServerId(serverId)
                toolBindings().filter { it.serverId == resolvedServerId }
                    .ifEmpty { error("MCP server '$serverId' is not connected.") }
            }
            JSONObject().apply {
                put("ok", true)
                put(
                    "tools",
                    JSONArray().apply {
                        tools.forEach { binding ->
                            put(
                                JSONObject().apply {
                                    put("server_id", binding.serverId)
                                    put("server_name", binding.serverName)
                                    put("tool_name", binding.toolName)
                                    put("description", binding.description)
                                    put("call_name", binding.namespacedToolName)
                                    put("legacy_call_name", binding.legacyToolName)
                                    put("input_schema", JSONObject(binding.inputSchema.toString()))
                                },
                            )
                        }
                    },
                )
                put("stdout", "Listed ${tools.size} MCP tools.")
            }.toString()
        }
    }

    suspend fun callNamespacedTool(
        namespacedToolName: String,
        argumentsJson: String,
    ): Result<String> = callToolByName(namespacedToolName, argumentsJson)

    suspend fun callToolByName(
        toolCallName: String,
        argumentsJson: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val binding = resolveToolBinding(toolCallName)
                ?: error("Unknown MCP tool '$toolCallName'.")
            val arguments = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
            val result = sessions[binding.serverId]
                ?.callTool(binding.toolName, arguments)
                ?: error("MCP server '${binding.serverId}' is not connected.")
            result.toString()
        }
    }

    suspend fun callTool(
        serverId: String,
        toolName: String,
        arguments: JSONObject,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolvedServerId = resolveServerId(serverId)
            val result = sessions[resolvedServerId]
                ?.callTool(toolName, arguments)
                ?: error("MCP server '$serverId' is not connected.")
            result.toString()
        }
    }

    suspend fun listResources(
        serverId: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resources = if (serverId.isNullOrBlank()) {
                sessions.values.flatMap { it.snapshot.resources }
            } else {
                sessions[serverId]?.snapshot?.resources
                    ?: error("MCP server '$serverId' is not connected.")
            }
            JSONObject().apply {
                put("ok", true)
                put(
                    "resources",
                    JSONArray().apply {
                        resources.forEach { resource ->
                            put(
                                JSONObject().apply {
                                    put("server_id", resource.serverId)
                                    put("server_name", resource.serverName)
                                    put("uri", resource.uri)
                                    put("name", resource.name)
                                    put("description", resource.description)
                                    put("mime_type", resource.mimeType)
                                },
                            )
                        }
                    },
                )
                put("stdout", "Listed ${resources.size} MCP resources.")
            }.toString()
        }
    }

    suspend fun readResource(
        serverId: String,
        uri: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val result = sessions[serverId]?.readResource(uri)
                ?: error("MCP server '$serverId' is not connected.")
            result.toString()
        }
    }

    suspend fun listPrompts(
        serverId: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val prompts = if (serverId.isNullOrBlank()) {
                sessions.values.flatMap { it.snapshot.prompts }
            } else {
                sessions[serverId]?.snapshot?.prompts
                    ?: error("MCP server '$serverId' is not connected.")
            }
            JSONObject().apply {
                put("ok", true)
                put(
                    "prompts",
                    JSONArray().apply {
                        prompts.forEach { prompt ->
                            put(
                                JSONObject().apply {
                                    put("server_id", prompt.serverId)
                                    put("server_name", prompt.serverName)
                                    put("name", prompt.name)
                                    put("description", prompt.description)
                                    put("arguments", JSONArray(prompt.arguments.toString()))
                                },
                            )
                        }
                    },
                )
                put("stdout", "Listed ${prompts.size} MCP prompts.")
            }.toString()
        }
    }

    suspend fun getPrompt(
        serverId: String,
        promptName: String,
        arguments: JSONObject,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val result = sessions[serverId]?.getPrompt(promptName, arguments)
                ?: error("MCP server '$serverId' is not connected.")
            result.toString()
        }
    }

    private fun createTransport(
        server: McpServerConfig,
        workspaceDirectory: String,
    ): McpSessionTransport = when (val transportConfig = server.transport) {
        is McpTransportConfig.StdIo -> StdIoMcpTransport(
            serverId = server.id,
            config = transportConfig,
            workspaceDirectory = workspaceDirectory,
            bashTool = bashTool,
        )

        is McpTransportConfig.StreamableHttp -> StreamableHttpMcpTransport(
            config = transportConfig,
            protocolVersion = DefaultMcpProtocolVersion,
            httpClient = httpClient,
        )
    }

    private fun resolveToolBinding(toolCallName: String): McpToolBinding? =
        toolBindings().firstOrNull { it.matchesToolCallName(toolCallName) }

    private fun resolveServerId(serverId: String): String {
        val normalizedServerId = serverId.trim()
        if (normalizedServerId.isBlank()) {
            error("MCP server id is required.")
        }
        sessions[normalizedServerId]?.let { return it.config.id }
        return sessions.values.firstOrNull {
            it.config.displayName.equals(normalizedServerId, ignoreCase = true)
        }?.config?.id ?: normalizedServerId
    }
}

private class McpServerSession(
    val config: McpServerConfig,
    private val transport: McpSessionTransport,
    val workspaceDirectory: String,
    private val callbacks: McpClientCallbacks,
) {
    private var requestCounter = 1L
    private var initialized = false
    var snapshot: McpServerSnapshot = McpServerSnapshot(config = config)
        private set

    suspend fun connectAndRefresh() {
        snapshot = snapshot.copy(status = McpConnectionStatus.Connecting, errorMessage = "")
        runCatching {
            transport.open()
            val initializeResult = call(
                method = "initialize",
                params = JSONObject().apply {
                    put("protocolVersion", DefaultMcpProtocolVersion)
                    put(
                        "capabilities",
                        JSONObject().apply {
                            put("roots", JSONObject().apply { put("listChanged", true) })
                            put("sampling", JSONObject().apply { put("supported", true) })
                            put("elicitation", JSONObject().apply { put("supported", true) })
                        },
                    )
                    put(
                        "clientInfo",
                        JSONObject().apply {
                            put("name", "Aether Android")
                            put("version", "0.1.0")
                        },
                    )
                },
            )
            initialized = true
            sendNotification("notifications/initialized")
            val protocolVersion = initializeResult.optString("protocolVersion")
            val serverInfo = initializeResult.optJSONObject("serverInfo")
                ?.let { "${it.optString("name")} ${it.optString("version")}".trim() }
                .orEmpty()
            snapshot = snapshot.copy(
                status = McpConnectionStatus.Ready,
                protocolVersion = protocolVersion,
                serverInfo = serverInfo,
                errorMessage = "",
            )
            refreshCatalog()
        }.onFailure { throwable ->
            snapshot = snapshot.copy(
                status = McpConnectionStatus.Error,
                errorMessage = throwable.message ?: "Couldn't connect to MCP server.",
            )
        }
    }

    suspend fun refreshCatalog() {
        if (!initialized) return
        runCatching {
            val toolsResult = call("tools/list")
            val resourcesResult = call("resources/list")
            val promptsResult = call("prompts/list")
            snapshot = snapshot.copy(
                status = McpConnectionStatus.Ready,
                tools = parseTools(config.id, config.displayName, toolsResult),
                resources = parseResources(config.id, config.displayName, resourcesResult),
                prompts = parsePrompts(config.id, config.displayName, promptsResult),
            )
        }.onFailure { throwable ->
            snapshot = snapshot.copy(
                status = McpConnectionStatus.Error,
                errorMessage = throwable.message ?: "Couldn't refresh MCP server catalog.",
            )
        }
    }

    suspend fun callTool(
        toolName: String,
        arguments: JSONObject,
    ): JSONObject {
        val result = call(
            method = "tools/call",
            params = JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            },
        )
        return JSONObject().apply {
            put("ok", true)
            put("server_id", config.id)
            put("server_name", config.displayName)
            put("tool_name", toolName)
            put("result", result)
            put("stdout", "Called MCP tool ${config.displayName}/$toolName.")
        }
    }

    suspend fun readResource(uri: String): JSONObject {
        val result = call(
            method = "resources/read",
            params = JSONObject().apply { put("uri", uri) },
        )
        return JSONObject().apply {
            put("ok", true)
            put("server_id", config.id)
            put("uri", uri)
            put("result", result)
            put("stdout", "Read MCP resource $uri.")
        }
    }

    suspend fun getPrompt(
        promptName: String,
        arguments: JSONObject,
    ): JSONObject {
        val result = call(
            method = "prompts/get",
            params = JSONObject().apply {
                put("name", promptName)
                put("arguments", arguments)
            },
        )
        return JSONObject().apply {
            put("ok", true)
            put("server_id", config.id)
            put("name", promptName)
            put("result", result)
            put("stdout", "Fetched MCP prompt ${config.displayName}/$promptName.")
        }
    }

    suspend fun close() {
        runCatching { transport.close() }
        snapshot = snapshot.copy(status = McpConnectionStatus.Disconnected)
    }

    private suspend fun sendNotification(method: String) {
        transport.sendMessage(
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
            },
        )
    }

    private suspend fun call(
        method: String,
        params: JSONObject? = null,
    ): JSONObject {
        val requestId = nextRequestId()
        val startedAt = System.currentTimeMillis()
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            if (params != null) {
                put("params", params)
            }
        }
        logMcp("[${
            config.id
        }] -> method=$method id=$requestId")

        var messages = transport.sendMessage(request)
        val deadline = System.currentTimeMillis() + config.requestTimeoutMillis.coerceAtLeast(
            McpDefaultRequestTimeoutMillis,
        )

        while (System.currentTimeMillis() < deadline) {
            val result = consumeMessages(requestId, messages)
            if (result != null) {
                logMcp(
                    "[${config.id}] <- method=$method id=$requestId " +
                        "duration_ms=${System.currentTimeMillis() - startedAt}",
                )
                return result
            }
            delay(McpRequestPollIntervalMillis)
            messages = transport.pollMessages()
        }

        logMcp(
            "[${config.id}] timeout method=$method id=$requestId " +
                "duration_ms=${System.currentTimeMillis() - startedAt}",
        )
        error("Timed out waiting for MCP response to '$method'.")
    }

    private suspend fun consumeMessages(
        requestId: String,
        messages: List<JSONObject>,
    ): JSONObject? {
        for (message in messages) {
            if (message.optString("jsonrpc") != "2.0") continue
            if (message.has("id") && message.opt("id")?.toString() == requestId) {
                if (message.has("error")) {
                    val errorMessage = message.optJSONObject("error")
                        ?.optString("message")
                        .orEmpty()
                        .ifBlank { "MCP request failed." }
                    error(errorMessage)
                }
                return message.optJSONObject("result") ?: JSONObject()
            }
            if (message.has("method")) {
                val method = message.optString("method")
                val params = message.optJSONObject("params") ?: JSONObject()
                when {
                    method == "roots/list" -> respondToServerRequest(
                        id = message.opt("id"),
                        result = JSONObject().apply {
                            put(
                                "roots",
                                JSONArray().apply {
                                    callbacks.listRoots(workspaceDirectory).forEach { root ->
                                        put(
                                            JSONObject().apply {
                                                put("uri", "file://$root")
                                                put("name", root.substringAfterLast('/').ifBlank { "workspace" })
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )

                    method == "sampling/createMessage" -> respondToServerRequest(
                        id = message.opt("id"),
                        result = callbacks.handleSamplingRequest(config.id, params),
                    )

                    method == "elicitation/create" -> respondToServerRequest(
                        id = message.opt("id"),
                        result = callbacks.handleElicitationRequest(config.id, params),
                    )

                    method.startsWith("notifications/") -> processNotification(method, params)
                    method.startsWith("tasks/") -> processTaskMessage(method, params)
                }
            }
        }
        return null
    }

    private suspend fun respondToServerRequest(
        id: Any?,
        result: JSONObject?,
    ) {
        if (id == null) return
        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result != null) {
                put("result", result)
            } else {
                put(
                    "error",
                    JSONObject().apply {
                        put("code", -32000)
                        put("message", "Request denied by client.")
                    },
                )
            }
        }
        transport.sendMessage(response)
    }

    private fun processNotification(
        method: String,
        params: JSONObject,
    ) {
        when (method) {
            "notifications/message",
            "notifications/logging/message" -> {
                val level = params.optString("level").ifBlank { "info" }
                val logger = params.optString("logger")
                val data = params.opt("data")?.toString().orEmpty()
                snapshot = snapshot.copy(
                    logs = (snapshot.logs + McpLogEvent(config.id, level, logger, data)).takeLast(100),
                )
            }
        }
    }

    private fun processTaskMessage(
        method: String,
        params: JSONObject,
    ) {
        val taskId = params.optString("taskId")
            .ifBlank { params.optString("id") }
            .ifBlank { return }
        val tasks = snapshot.tasks.associateBy { it.taskId }.toMutableMap()
        tasks[taskId] = McpTaskState(
            serverId = config.id,
            taskId = taskId,
            status = method.substringAfterLast('/'),
            title = params.optString("title"),
            detail = params.toString(),
        )
        snapshot = snapshot.copy(tasks = tasks.values.sortedBy { it.taskId })
    }

    private fun nextRequestId(): String = "${config.id}-${requestCounter++}"
}

private interface McpSessionTransport {
    suspend fun open()

    suspend fun sendMessage(message: JSONObject): List<JSONObject>

    suspend fun pollMessages(): List<JSONObject>

    suspend fun close()
}

private class StreamableHttpMcpTransport(
    private val config: McpTransportConfig.StreamableHttp,
    private val protocolVersion: String,
    private val httpClient: OkHttpClient,
) : McpSessionTransport {
    private var sessionId: String = ""

    override suspend fun open() = Unit

    override suspend fun sendMessage(message: JSONObject): List<JSONObject> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("MCP-Protocol-Version", protocolVersion)
            .apply {
                if (sessionId.isNotBlank()) {
                    addHeader("Mcp-Session-Id", sessionId)
                }
                config.headers.forEach { addHeader(it.key, it.value) }
            }
            .post(message.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            response.header("Mcp-Session-Id")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { sessionId = it }
            val body = response.body
            if (!response.isSuccessful) {
                error("MCP server returned HTTP ${response.code}.")
            }
            if (body == null || body.contentLength() == 0L) {
                return@withContext emptyList()
            }
            val contentType = response.header("Content-Type").orEmpty()
            val responseText = body.string()
            if (responseText.isBlank()) {
                return@withContext emptyList()
            }
            return@withContext if (contentType.contains("text/event-stream", ignoreCase = true)) {
                parseSseMessages(responseText)
            } else {
                listOf(JSONObject(responseText))
            }
        }
    }

    override suspend fun pollMessages(): List<JSONObject> = emptyList()

    override suspend fun close() = Unit
}

private class StdIoMcpTransport(
    private val serverId: String,
    private val config: McpTransportConfig.StdIo,
    private val workspaceDirectory: String,
    private val bashTool: TermuxBashTool,
) : McpSessionTransport {
    private var runId: String = ""
    private var logOffset: Long = 0L
    private var lastHealthLog: String = ""

    override suspend fun open() {
        val launchResult = JSONObject(
            bashTool.execute(
                JSONObject().apply {
                    put("command", buildBrokerScript())
                    put("working_directory", TermuxContract.HomeDirectory)
                }.toString(),
            ),
        )
        if (!launchResult.optBoolean("ok") && launchResult.optString("status") != "running") {
            error(launchResult.optString("errmsg").ifBlank { "Couldn't launch the MCP stdio broker." })
        }
        runId = launchResult.optString("run_id")
    }

    override suspend fun sendMessage(message: JSONObject): List<JSONObject> {
        logMcp("[$serverId] send ${describeMcpMessage(message)}")
        val payloadBase64 = encodeBase64(message.toString())
        val command = buildString {
            appendLine("set -euo pipefail")
            appendLine("root='${brokerRootPath()}'")
            appendLine("mkdir -p \"\$root/inbox\"")
            appendLine("payload=\"\$(printf '%s' '$payloadBase64' | base64 -d)\"")
            appendLine("request_path=\"\$root/inbox/${System.currentTimeMillis()}-${UUID.randomUUID()}.json\"")
            appendLine("temp_request_path=\"\$root/.request-${UUID.randomUUID()}.tmp\"")
            appendLine("printf '%s' \"\$payload\" > \"\$temp_request_path\"")
            appendLine("mv \"\$temp_request_path\" \"\$request_path\"")
        }
        val rawResult = JSONObject(bashTool.executeCommand(command))
        if (!rawResult.optBoolean("ok")) {
            error(rawResult.optString("errmsg").ifBlank { "Couldn't write to the MCP broker inbox." })
        }
        return emptyList()
    }

    override suspend fun pollMessages(): List<JSONObject> {
        val command = buildString {
            appendLine("set -euo pipefail")
            appendLine("root='${brokerRootPath()}'")
            appendLine("events_path=\"\$root/events.jsonl\"")
            appendLine("stderr_path=\"\$root/stderr.log\"")
            appendLine("server_pid_path=\"\$root/server.pid\"")
            appendLine("current_offset=$logOffset")
            appendLine("server_pid=''")
            appendLine("if [ -f \"\$server_pid_path\" ]; then")
            appendLine("  server_pid=\"\$(tr -d '[:space:]' < \"\$server_pid_path\")\"")
            appendLine("fi")
            appendLine("server_alive=false")
            appendLine("if [ -n \"\$server_pid\" ] && kill -0 \"\$server_pid\" 2>/dev/null; then")
            appendLine("  server_alive=true")
            appendLine("fi")
            appendLine("inbox_count=\$(find \"\$root/inbox\" -maxdepth 1 -type f -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')")
            appendLine("processed_count=\$(find \"\$root/processed\" -maxdepth 1 -type f -name '*.json' 2>/dev/null | wc -l | tr -d '[:space:]')")
            appendLine("stderr_b64=''")
            appendLine("if [ -f \"\$stderr_path\" ]; then")
            appendLine("  stderr_b64=\"\$(tail -c 4096 -- \"\$stderr_path\" 2>/dev/null | base64 | tr -d '\\n')\"")
            appendLine("fi")
            appendLine("if [ ! -f \"\$events_path\" ]; then")
            appendLine("  printf 'offset=%s\\n' \"\$current_offset\"")
            appendLine("  printf 'server_pid=%s\\n' \"\$server_pid\"")
            appendLine("  printf 'server_alive=%s\\n' \"\$server_alive\"")
            appendLine("  printf 'inbox_count=%s\\n' \"\$inbox_count\"")
            appendLine("  printf 'processed_count=%s\\n' \"\$processed_count\"")
            appendLine("  printf 'stderr_b64=%s\\n' \"\$stderr_b64\"")
            appendLine("  printf 'events_b64=\\n'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("size=\$(wc -c < \"\$events_path\" | tr -d '[:space:]')")
            appendLine("if [ \"\$size\" -le \"\$current_offset\" ]; then")
            appendLine("  printf 'offset=%s\\n' \"\$size\"")
            appendLine("  printf 'server_pid=%s\\n' \"\$server_pid\"")
            appendLine("  printf 'server_alive=%s\\n' \"\$server_alive\"")
            appendLine("  printf 'inbox_count=%s\\n' \"\$inbox_count\"")
            appendLine("  printf 'processed_count=%s\\n' \"\$processed_count\"")
            appendLine("  printf 'stderr_b64=%s\\n' \"\$stderr_b64\"")
            appendLine("  printf 'events_b64=\\n'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("byte_count=\$((size - current_offset))")
            appendLine("chunk=\"\$(dd if=\"\$events_path\" bs=1 skip=\"\$current_offset\" count=\"\$byte_count\" status=none 2>/dev/null; printf '\\037')\"")
            appendLine("chunk=\"\${chunk%\$'\\037'}\"")
            appendLine("complete_chunk=''")
            appendLine("case \"\$chunk\" in")
            appendLine("  *$'\\n')")
            appendLine("    complete_chunk=\"\$chunk\"")
            appendLine("    ;;")
            appendLine("  *$'\\n'*)")
            appendLine("    complete_chunk=\"\${chunk%$'\\n'*}\"\$'\\n'")
            appendLine("    ;;")
            appendLine("esac")
            appendLine("complete_bytes=\$(printf '%s' \"\$complete_chunk\" | wc -c | tr -d '[:space:]')")
            appendLine("next_offset=\$((current_offset + complete_bytes))")
            appendLine("printf 'offset=%s\\n' \"\$next_offset\"")
            appendLine("printf 'server_pid=%s\\n' \"\$server_pid\"")
            appendLine("printf 'server_alive=%s\\n' \"\$server_alive\"")
            appendLine("printf 'inbox_count=%s\\n' \"\$inbox_count\"")
            appendLine("printf 'processed_count=%s\\n' \"\$processed_count\"")
            appendLine("printf 'stderr_b64=%s\\n' \"\$stderr_b64\"")
            appendLine("printf 'events_b64=%s\\n' \"\$(printf '%s' \"\$complete_chunk\" | base64 | tr -d '\\n')\"")
        }
        val rawResult = JSONObject(bashTool.executeCommand(command))
        if (!rawResult.optBoolean("ok")) {
            error(rawResult.optString("errmsg").ifBlank { "Couldn't read from the MCP broker event log." })
        }
        val values = parseKeyValueOutput(rawResult.optString("stdout"))
        logOffset = values["offset"]?.toLongOrNull() ?: logOffset
        logHealth(values)
        val events = decodeBase64(values["events_b64"].orEmpty())
        if (events.isBlank()) return emptyList()
        return events.lineSequence()
            .mapNotNull { line ->
                val payloadBase64 = line.substringAfter('|', "")
                if (payloadBase64.isBlank()) {
                    null
                } else {
                    runCatching { JSONObject(decodeBase64(payloadBase64)) }.getOrNull()
                }
            }
            .toList()
            .also { messages ->
                if (messages.isNotEmpty()) {
                    logMcp(
                        "[$serverId] poll offset=$logOffset messages=" +
                            messages.joinToString(separator = "; ") { describeMcpMessage(it) },
                    )
                }
            }
    }

    override suspend fun close() {
        if (runId.isBlank()) return
        runCatching {
            bashTool.killExecution(
                JSONObject().apply {
                    put("run_id", runId)
                }.toString(),
            )
        }
    }

    private fun buildBrokerScript(): String = buildString {
        appendLine("set -euo pipefail")
        appendLine("root='${brokerRootPath()}'")
        appendLine("mkdir -p \"\$root\"")
        appendLine("rm -rf \"\$root/inbox\" \"\$root/processed\"")
        appendLine("rm -f \"\$root\"/.request-*.tmp \"\$root/server.pid\"")
        appendLine("mkdir -p \"\$root/inbox\" \"\$root/processed\"")
        appendLine("events_path=\"\$root/events.jsonl\"")
        appendLine("stderr_path=\"\$root/stderr.log\"")
        appendLine("rm -f \"\$root/server.stdin\" \"\$root/server.stdout\"")
        appendLine(": > \"\$events_path\"")
        appendLine(": > \"\$stderr_path\"")
        config.environment.forEach { keyValue ->
            appendLine("export ${escapeShellName(keyValue.key)}='${escapeForSingleQuoted(keyValue.value)}'")
        }
        appendLine("command='${escapeForSingleQuoted(config.command)}'")
        appendLine("working_directory='${escapeForSingleQuoted(config.workingDirectory.ifBlank { workspaceDirectory })}'")
        appendLine("coproc SERVER_PROCESS {")
        appendLine("  cd \"\$working_directory\"")
        appendLine("  bash -lc \"\$command\" 2>> \"\$stderr_path\"")
        appendLine("}")
        appendLine("server_pid=\$SERVER_PROCESS_PID")
        appendLine("exec {server_stdin_fd}>&\"\${SERVER_PROCESS[1]}\"")
        appendLine("exec {server_stdout_fd}<&\"\${SERVER_PROCESS[0]}\"")
        appendLine("stdin_fd=\"\$server_stdin_fd\"")
        appendLine("stdout_fd=\"\$server_stdout_fd\"")
        appendLine("printf '%s' \"\$server_pid\" > \"\$root/server.pid\"")
        appendLine("(")
        appendLine("  while true; do")
        appendLine("    found=false")
        appendLine("    for request_path in \"\$root\"/inbox/*.json; do")
        appendLine("      [ -f \"\$request_path\" ] || continue")
        appendLine("      found=true")
        appendLine("      payload=\"\$(cat \"\$request_path\")\"")
        appendLine("      payload_bytes=\$(printf '%s' \"\$payload\" | wc -c | tr -d '[:space:]')")
        appendLine("      printf 'Content-Length: %s\\r\\n\\r\\n%s' \"\$payload_bytes\" \"\$payload\" >&\"\$stdin_fd\"")
        appendLine("      mv \"\$request_path\" \"\$root/processed/\"")
        appendLine("    done")
        appendLine("    [ \"\$found\" = true ] || sleep 0.1")
        appendLine("  done")
        appendLine(") &")
        appendLine("writer_pid=\$!")
        appendLine("sequence=0")
        appendLine("while true; do")
        appendLine("  content_length=''")
        appendLine("  while IFS= read -r line <&\"\$stdout_fd\"; do")
        appendLine("    line=\"\${line%$'\\r'}\"")
        appendLine("    [ -z \"\$line\" ] && break")
        appendLine("    case \"\$line\" in")
        appendLine("      Content-Length:*) content_length=\"\${line#Content-Length: }\" ;;")
        appendLine("    esac")
        appendLine("  done || break")
        appendLine("  [ -n \"\$content_length\" ] || continue")
        appendLine("  payload=\"\$(dd bs=1 count=\"\$content_length\" iflag=fullblock 2>/dev/null <&\"\$stdout_fd\"; printf '\\037')\"")
        appendLine("  payload=\"\${payload%\$'\\037'}\"")
        appendLine("  payload_b64=\"\$(printf '%s' \"\$payload\" | base64 | tr -d '\\n')\"")
        appendLine("  sequence=\$((sequence + 1))")
        appendLine("  printf '%s|%s\\n' \"\$sequence\" \"\$payload_b64\" >> \"\$events_path\"")
        appendLine("done")
        appendLine("kill \"\$writer_pid\" 2>/dev/null || true")
        appendLine("kill \"\$server_pid\" 2>/dev/null || true")
    }

    private fun brokerRootPath(): String =
        "${TermuxContract.HomeDirectory}/.aether/mcp-brokers/$serverId"

    private fun logHealth(values: Map<String, String>) {
        val serverPid = values["server_pid"].orEmpty().ifBlank { "?" }
        val serverAlive = values["server_alive"].orEmpty().ifBlank { "unknown" }
        val inboxCount = values["inbox_count"].orEmpty().ifBlank { "?" }
        val processedCount = values["processed_count"].orEmpty().ifBlank { "?" }
        val stderrTail = decodeBase64(values["stderr_b64"].orEmpty())
            .trim()
            .lineSequence()
            .toList()
            .takeLast(3)
            .joinToString(separator = " | ")
            .ifBlank { "<empty>" }
        val summary = "[$serverId] broker pid=$serverPid alive=$serverAlive inbox=$inboxCount processed=$processedCount stderr=$stderrTail"
        if (summary != lastHealthLog) {
            lastHealthLog = summary
            logMcp(summary)
        }
    }

    private fun escapeForSingleQuoted(value: String): String =
        value.replace("'", "'\"'\"'")

    private fun escapeShellName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "MCP_VALUE" }
}

private fun parseTools(
    serverId: String,
    serverName: String,
    response: JSONObject,
): List<McpToolBinding> {
    val tools = response.optJSONArray("tools") ?: JSONArray()
    return buildList {
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            add(
                McpToolBinding(
                    serverId = serverId,
                    serverName = serverName,
                    toolName = tool.optString("name"),
                    description = tool.optString("description"),
                    inputSchema = tool.optJSONObject("inputSchema")
                        ?: tool.optJSONObject("input_schema")
                        ?: JSONObject().apply { put("type", "object") },
                ),
            )
        }
    }
}

private fun parseResources(
    serverId: String,
    serverName: String,
    response: JSONObject,
): List<McpResourceItem> {
    val resources = response.optJSONArray("resources") ?: JSONArray()
    return buildList {
        for (index in 0 until resources.length()) {
            val resource = resources.optJSONObject(index) ?: continue
            add(
                McpResourceItem(
                    serverId = serverId,
                    serverName = serverName,
                    uri = resource.optString("uri"),
                    name = resource.optString("name"),
                    description = resource.optString("description"),
                    mimeType = resource.optString("mimeType")
                        .ifBlank { resource.optString("mime_type") },
                ),
            )
        }
    }
}

private fun parsePrompts(
    serverId: String,
    serverName: String,
    response: JSONObject,
): List<McpPromptItem> {
    val prompts = response.optJSONArray("prompts") ?: JSONArray()
    return buildList {
        for (index in 0 until prompts.length()) {
            val prompt = prompts.optJSONObject(index) ?: continue
            add(
                McpPromptItem(
                    serverId = serverId,
                    serverName = serverName,
                    name = prompt.optString("name"),
                    description = prompt.optString("description"),
                    arguments = prompt.optJSONArray("arguments") ?: JSONArray(),
                ),
            )
        }
    }
}

private fun parseSseMessages(rawValue: String): List<JSONObject> {
    val results = mutableListOf<JSONObject>()
    val eventData = StringBuilder()
    rawValue.lineSequence().forEach { line ->
        if (line.isEmpty()) {
            if (eventData.isNotEmpty()) {
                runCatching { JSONObject(eventData.toString()) }.getOrNull()?.let(results::add)
                eventData.setLength(0)
            }
            return@forEach
        }
        if (line.startsWith(":")) return@forEach
        if (!line.startsWith("data:")) return@forEach
        if (eventData.isNotEmpty()) {
            eventData.append('\n')
        }
        eventData.append(line.removePrefix("data:").trimStart())
    }
    if (eventData.isNotEmpty()) {
        runCatching { JSONObject(eventData.toString()) }.getOrNull()?.let(results::add)
    }
    return results
}

private fun encodeBase64(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeBase64(value: String): String =
    if (value.isBlank()) {
        ""
    } else {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    }

private fun describeMcpMessage(message: JSONObject): String = buildString {
    val method = message.optString("method").trim()
    val id = message.opt("id")?.toString().orEmpty()
    if (method.isNotEmpty()) {
        append("method=")
        append(method)
    } else {
        append("response")
    }
    if (id.isNotEmpty()) {
        append(" id=")
        append(id)
    }
    when {
        message.has("result") -> append(" result")
        message.has("error") -> append(" error")
    }
}

private fun validateMcpServerConfig(server: McpServerConfig) {
    if (server.displayName.trim().isBlank()) {
        error("Missing required field: server name.")
    }
    when (val transport = server.transport) {
        is McpTransportConfig.StreamableHttp -> {
            val url = transport.url.trim()
            if (url.isBlank()) {
                error("Missing required field: server URL.")
            }
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                error("Format error: server URL must start with http:// or https://.")
            }
        }

        is McpTransportConfig.StdIo -> {
            if (transport.command.trim().isBlank()) {
                error("Missing required field: command.")
            }
        }
    }
}

/**
 * 输出 MCP 调试日志，并交由统一日志工具脱敏。
 */
private fun logMcp(message: String) {
    if (EnableMcpLogging) {
        AetherLog.d(McpLogTag, message)
    }
}

private fun parseKeyValueOutput(stdout: String): Map<String, String> = buildMap {
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
