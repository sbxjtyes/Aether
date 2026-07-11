package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhousl.aether.ui.AttachmentKind
import com.zhousl.aether.ui.AttachmentWorkspaceState
import com.zhousl.aether.ui.ChatAttachment
import com.zhousl.aether.ui.ChatBranchGroup
import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatSession
import com.zhousl.aether.ui.ChatToolInvocation
import com.zhousl.aether.ui.MessageAuthor
import com.zhousl.aether.ui.ReasoningSummaryChunk
import com.zhousl.aether.ui.ReasoningTrace
import com.zhousl.aether.ui.syncActiveBranches
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DraftSessionId = "draft"
private const val PersistedReasoningRawTextMaxChars = 12_000

// 持久化时工具输出的体积上限。工具（如 bash/read/grep）的输出可能非常大，
// 原样写入会导致会话文件膨胀与内存放大；超过上限时截断并标注，运行期 UI 仍使用完整内存值。
private const val PersistedToolOutputMaxChars = 24_000

private val Context.chatDataStore by preferencesDataStore(name = "aether_chats")

data class PersistedChatState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String = DraftSessionId,
)

/**
 * 聊天持久化层。
 *
 * 存储模型由“单键整包 JSON”重构为“按会话拆分的文件 + 索引文件”，实现行级（按会话）增量写：
 * 修改一条消息只重写它所属会话的文件，而不再每次序列化并落盘全部会话，显著降低写放大与 CPU。
 *
 * 兼容旧版：首次加载若发现旧的 DataStore 整包数据，会迁移到新的文件存储，并保留旧数据作为备份（不删除），
 * 以避免任何迁移异常导致历史会话丢失。
 */
class ChatRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val storeDirectory: File by lazy {
        File(context.filesDir, ChatStoreDirectoryName).apply { mkdirs() }
    }
    private val ioMutex = Mutex()
    private val lastWrittenSessionJson = mutableMapOf<String, String>()
    @Volatile
    private var loaded = false

    private val _chatState = MutableStateFlow<PersistedChatState?>(null)
    val chatState: Flow<PersistedChatState> = _chatState.filterNotNull()

    init {
        scope.launch {
            val initial = ioMutex.withLock { ensureLoadedLocked() }
            _chatState.value = initial
        }
    }

    suspend fun updateChatState(
        sessions: List<ChatSession>,
        currentSessionId: String,
    ) {
        ioMutex.withLock {
            ensureLoadedLocked()
            writeSessionsIncrementallyLocked(sessions, currentSessionId)
        }
        _chatState.value = PersistedChatState(sessions = sessions, currentSessionId = currentSessionId)
    }

    /** 确保已从磁盘加载（含旧数据迁移）。返回当前持久化状态。须在持有 ioMutex 时调用。 */
    private suspend fun ensureLoadedLocked(): PersistedChatState {
        if (loaded) {
            return readStateFromDiskLocked()
        }
        migrateFromLegacyDataStoreIfNeededLocked()
        loaded = true
        return readStateFromDiskLocked()
    }

    private fun readStateFromDiskLocked(): PersistedChatState {
        val index = readIndex()
        val sessionsById = mutableMapOf<String, ChatSession>()
        storeDirectory.listFiles()?.forEach { file ->
            if (!file.name.startsWith(SessionFilePrefix) || !file.name.endsWith(SessionFileSuffix)) return@forEach
            val content = runCatching { file.readText() }.getOrNull().orEmpty()
            if (content.isBlank()) return@forEach
            val session = runCatching { parseChatSessionObject(JSONObject(content)) }.getOrNull() ?: return@forEach
            sessionsById[session.id] = session
            lastWrittenSessionJson[session.id] = content
        }
        // 按索引顺序排列；索引缺失的会话补在末尾。
        val ordered = buildList {
            index.order.forEach { id -> sessionsById.remove(id)?.let(::add) }
            sessionsById.values.forEach(::add)
        }
        val resolvedCurrent = index.currentSessionId
            .takeIf { id -> id == DraftSessionId || ordered.any { it.id == id } }
            ?: ordered.firstOrNull()?.id
            ?: DraftSessionId
        return PersistedChatState(sessions = ordered, currentSessionId = resolvedCurrent)
    }

    private fun writeSessionsIncrementallyLocked(
        sessions: List<ChatSession>,
        currentSessionId: String,
    ) {
        val incomingIds = HashSet<String>(sessions.size)
        sessions.forEach { session ->
            incomingIds += session.id
            val json = session.toJson().toString()
            if (lastWrittenSessionJson[session.id] == json) return@forEach
            writeFileAtomically(sessionFile(session.id), json)
            lastWrittenSessionJson[session.id] = json
        }
        // 删除已不存在的会话文件。
        val removedIds = lastWrittenSessionJson.keys - incomingIds
        removedIds.forEach { id ->
            runCatching { sessionFile(id).delete() }
            lastWrittenSessionJson.remove(id)
        }
        writeIndex(ChatIndex(order = sessions.map { it.id }, currentSessionId = currentSessionId))
    }

    private fun readIndex(): ChatIndex {
        val file = indexFile()
        if (!file.exists()) return ChatIndex()
        val content = runCatching { file.readText() }.getOrNull().orEmpty()
        if (content.isBlank()) return ChatIndex()
        return runCatching {
            val json = JSONObject(content)
            ChatIndex(
                order = parseStringList(json.optJSONArray("order")),
                currentSessionId = json.optString("currentSessionId").ifBlank { DraftSessionId },
            )
        }.getOrDefault(ChatIndex())
    }

    private fun writeIndex(index: ChatIndex) {
        val json = JSONObject().apply {
            put("order", JSONArray().apply { index.order.forEach(::put) })
            put("currentSessionId", index.currentSessionId)
        }
        writeFileAtomically(indexFile(), json.toString())
    }

    private suspend fun migrateFromLegacyDataStoreIfNeededLocked() {
        if (indexFile().exists()) return
        val legacy = runCatching {
            withContext(Dispatchers.IO) {
                val preferences = context.chatDataStore.data.first()
                val sessions = parseChatSessions(preferences[SESSIONS_JSON].orEmpty())
                val currentSessionId = preferences[CURRENT_SESSION_ID] ?: DraftSessionId
                PersistedChatState(sessions = sessions, currentSessionId = currentSessionId)
            }
        }.getOrNull() ?: PersistedChatState()
        // 写入新存储；旧 DataStore 数据保留不删，作为迁移后的安全备份。
        writeSessionsIncrementallyLocked(legacy.sessions, legacy.currentSessionId)
    }

    private fun sessionFile(sessionId: String): File =
        File(storeDirectory, "$SessionFilePrefix${sanitizeSessionFileName(sessionId)}$SessionFileSuffix")

    private fun indexFile(): File = File(storeDirectory, IndexFileName)

    private fun writeFileAtomically(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // 极少数文件系统 rename 失败时退化为直接写。
                target.writeText(content)
                tmp.delete()
            }
        }
    }

    private data class ChatIndex(
        val order: List<String> = emptyList(),
        val currentSessionId: String = DraftSessionId,
    )

    private companion object {
        const val ChatStoreDirectoryName = "chat-store"
        const val IndexFileName = "index.json"
        const val SessionFilePrefix = "s_"
        const val SessionFileSuffix = ".json"
        val SESSIONS_JSON = stringPreferencesKey("sessions_json")
        val CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")
    }
}

/** 把会话 id 转为安全的文件名片段（仅保留字母数字、下划线和连字符）。 */
private fun sanitizeSessionFileName(sessionId: String): String {
    val sanitized = sessionId.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
    }.joinToString("")
    // 加上原始 id 的哈希，避免不同 id 清洗后冲突。
    return "${sanitized.take(64)}_${sessionId.hashCode().toUInt()}"
}

internal fun parseChatSessions(rawValue: String): List<ChatSession> {
    if (rawValue.isBlank()) return emptyList()

    return runCatching {
        val sessions = JSONArray(rawValue)
        buildList {
            for (sessionIndex in 0 until sessions.length()) {
                val session = sessions.optJSONObject(sessionIndex) ?: continue
                add(parseChatSessionObject(session, fallbackIndex = sessionIndex))
            }
        }
    }.getOrElse { throwable ->
        listOf(corruptedChatStateSession(rawValue, throwable))
    }
}

internal fun parseChatSessionObject(
    session: JSONObject,
    fallbackIndex: Int = 0,
): ChatSession = ChatSession(
    id = session.optString("id").ifBlank { "session-$fallbackIndex" },
    title = session.optString("title"),
    preview = session.optString("preview"),
    hasCustomTitle = session.optBoolean("hasCustomTitle", false),
    messages = parseMessages(session.optJSONArray("messages")),
    selectedSkillIds = parseStringList(session.optJSONArray("selectedSkillIds")).ifEmpty {
        parseActiveSkillContexts(session.optString("activeSkillsJson")).map { it.skillId }
    },
    activeSkills = parseActiveSkillContexts(session.optString("activeSkillsJson")),
    activeMcpServerIds = parseStringList(session.optJSONArray("activeMcpServerIds")),
    agentModeEnabled = session.optBoolean("agentModeEnabled", false),
    enabledToolGroups = chatToolGroupsFromStored(
        if (session.has("enabledToolGroups")) {
            parseStringList(session.optJSONArray("enabledToolGroups"))
        } else {
            null
        },
    ),
    planModeEnabled = session.optBoolean("planModeEnabled", false),
    goalModeEnabled = session.optBoolean("goalModeEnabled", false),
    selectedModelKey = session.optString("selectedModelKey"),
    taskState = parseAgentTaskState(session.optJSONObject("taskState")),
    lastOpenedAtMillis = session.optLong("lastOpenedAtMillis"),
    lastActivityAtMillis = session.optLong("lastActivityAtMillis"),
    isPinned = session.optBoolean("isPinned", false),
    isArchived = session.optBoolean("isArchived", false),
)

private fun corruptedChatStateSession(
    rawValue: String,
    throwable: Throwable,
): ChatSession = ChatSession(
    id = "corrupt-chat-state-${rawValue.hashCode()}",
    title = "Chat storage needs recovery",
    preview = "Stored chat data could not be parsed.",
    hasCustomTitle = true,
    messages = listOf(
        ChatMessage(
            id = "agent-corrupt-chat-state-${rawValue.hashCode()}",
            author = MessageAuthor.Agent,
            text = "Aether could not read the stored chat history (${throwable.javaClass.simpleName}). " +
                "The app is showing this recovery placeholder instead of hiding the conversation list.",
            createdAtMillis = 0L,
        )
    ),
)

internal fun serializeChatSessions(sessions: List<ChatSession>): String =
    JSONArray().apply {
        sessions.forEach { session ->
            put(session.toJson())
        }
    }.toString()

internal fun ChatSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("preview", preview)
    put("hasCustomTitle", hasCustomTitle)
    put("lastOpenedAtMillis", lastOpenedAtMillis)
    put("lastActivityAtMillis", lastActivityAtMillis)
    put("isPinned", isPinned)
    put("isArchived", isArchived)
    put("agentModeEnabled", agentModeEnabled)
    put("planModeEnabled", planModeEnabled)
    put("goalModeEnabled", goalModeEnabled)
    put("selectedModelKey", selectedModelKey)
    put("selectedSkillIds", JSONArray().apply { selectedSkillIds.forEach(::put) })
    put("enabledToolGroups", JSONArray().apply { normalizeChatToolGroups(enabledToolGroups).forEach(::put) })
    put("messages", JSONArray().apply { syncActiveBranches(messages).forEach { put(it.toJson()) } })
    put("activeSkillsJson", serializeActiveSkillContexts(activeSkills))
    put("activeMcpServerIds", JSONArray().apply { activeMcpServerIds.forEach(::put) })
    if (!taskState.isEmpty) {
        put("taskState", taskState.toJson())
    }
}

private fun parseMessages(messages: JSONArray?): List<ChatMessage> {
    if (messages == null) return emptyList()

    return buildList {
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            add(
                ChatMessage(
                    id = message.optString("id").ifBlank { "message-$messageIndex" },
                    author = if (message.optString("author") == MessageAuthor.User.name) {
                        MessageAuthor.User
                    } else {
                        MessageAuthor.Agent
                    },
                    text = message.optString("text"),
                    createdAtMillis = message.optLong("createdAtMillis").takeIf { it > 0L }
                        ?: timestampFromMessageId(message.optString("id")),
                    attachments = parseAttachments(message.optJSONArray("attachments")),
                    toolInvocations = parseToolInvocations(message.optJSONArray("toolInvocations")),
                    thoughtDurationMillis = if (message.has("thoughtDurationMillis")) {
                        message.optLong("thoughtDurationMillis")
                    } else {
                        null
                    },
                    reasoningTrace = parseReasoningTrace(message.optJSONObject("reasoningTrace")),
                    branchGroup = parseBranchGroup(message.optJSONObject("branchGroup")),
                    responseGroupId = message.optString("responseGroupId").ifBlank { null },
                    assistantActionsHidden = message.optBoolean("assistantActionsHidden"),
                    tokenUsage = parseTokenUsageJson(message.optJSONObject("tokenUsage")),
                )
            )
        }
    }
}

private fun ChatMessage.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("author", author.name)
    put("text", text)
    if (createdAtMillis > 0L) {
        put("createdAtMillis", createdAtMillis)
    }
    thoughtDurationMillis?.let { put("thoughtDurationMillis", it) }
    reasoningTrace?.let { put("reasoningTrace", it.toJson()) }
    branchGroup?.let { put("branchGroup", it.toJson()) }
    responseGroupId?.let { put("responseGroupId", it) }
    if (assistantActionsHidden) {
        put("assistantActionsHidden", true)
    }
    tokenUsage?.takeIf { !it.isEmpty }?.let { put("tokenUsage", it.toJson()) }
    put("toolInvocations", JSONArray().apply { toolInvocations.forEach { put(it.toJson()) } })
    put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
}

private fun parseTokenUsageJson(json: JSONObject?): TokenUsage? {
    if (json == null) return null
    val usage = TokenUsage(
        promptTokens = json.optInt("promptTokens"),
        completionTokens = json.optInt("completionTokens"),
        totalTokens = json.optInt("totalTokens"),
    )
    return usage.takeIf { !it.isEmpty }
}

private fun TokenUsage.toJson(): JSONObject = JSONObject().apply {
    put("promptTokens", promptTokens)
    put("completionTokens", completionTokens)
    put("totalTokens", totalTokens)
}

private fun parseBranchGroup(json: JSONObject?): ChatBranchGroup? {
    if (json == null) return null
    val branchesJson = json.optJSONArray("branches") ?: return null
    val branches = buildList {
        for (index in 0 until branchesJson.length()) {
            add(parseMessages(branchesJson.optJSONArray(index)))
        }
    }.filter { it.isNotEmpty() }
    if (branches.size <= 1) return null
    return ChatBranchGroup(
        branches = branches,
        selectedIndex = json.optInt("selectedIndex", 0).coerceIn(0, branches.lastIndex),
    )
}

private fun ChatBranchGroup.toJson(): JSONObject = JSONObject().apply {
    val safeSelectedIndex = selectedIndex.coerceIn(0, branches.lastIndex.coerceAtLeast(0))
    put("selectedIndex", safeSelectedIndex)
    put(
        "branches",
        JSONArray().apply {
            branches.forEach { branch ->
                put(JSONArray().apply { branch.forEach { put(it.toJson()) } })
            }
        },
    )
}

private fun parseAttachments(attachments: JSONArray?): List<ChatAttachment> {
    if (attachments == null) return emptyList()

    return buildList {
        for (attachmentIndex in 0 until attachments.length()) {
            val attachment = attachments.optJSONObject(attachmentIndex) ?: continue
            val mimeType = attachment.optString("mimeType")
            val workspacePath = attachment.optString("workspacePath")
            add(
                ChatAttachment(
                    id = attachment.optString("id").ifBlank { "attachment-$attachmentIndex" },
                    uri = attachment.optString("uri"),
                    name = attachment.optString("name").ifBlank { "Attachment ${attachmentIndex + 1}" },
                    mimeType = mimeType,
                    sizeBytes = if (attachment.has("sizeBytes")) attachment.optLong("sizeBytes") else null,
                    kind = AttachmentKind.fromStored(
                        value = attachment.optString("kind"),
                        mimeType = mimeType,
                    ),
                    workspacePath = workspacePath,
                    workspaceState = if (workspacePath.isBlank()) {
                        AttachmentWorkspaceState.Failed
                    } else {
                        AttachmentWorkspaceState.Ready
                    },
                    workspaceError = if (workspacePath.isBlank()) {
                        "This attachment is missing its workspace copy."
                    } else {
                        ""
                    },
                )
            )
        }
    }
}

private fun ChatAttachment.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uri", uri)
    put("name", name)
    put("mimeType", mimeType)
    put("kind", kind.name)
    put("workspacePath", workspacePath)
    sizeBytes?.let { put("sizeBytes", it) }
}

private fun parseReasoningTrace(json: JSONObject?): ReasoningTrace? {
    if (json == null) return null
    val id = json.optString("id").ifBlank { "reasoning-${json.optString("startedAtMillis")}" }
    return ReasoningTrace(
        id = id,
        rawText = json.optString("rawText"),
        chunks = parseReasoningSummaryChunks(json.optJSONArray("chunks")),
        toolInvocations = parseToolInvocations(json.optJSONArray("toolInvocations")),
        latestStatusText = json.optString("latestStatusText"),
        startedAtMillis = json.optLong("startedAtMillis"),
        completedAtMillis = if (json.has("completedAtMillis")) {
            json.optLong("completedAtMillis")
        } else {
            null
        },
    )
}

private fun ReasoningTrace.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("rawText", if (hasSummary) "" else rawText.take(PersistedReasoningRawTextMaxChars))
    put("latestStatusText", latestStatusText)
    put("startedAtMillis", startedAtMillis)
    completedAtMillis?.let { put("completedAtMillis", it) }
    put("chunks", JSONArray().apply { chunks.forEach { put(it.toJson()) } })
    put("toolInvocations", JSONArray().apply { toolInvocations.forEach { put(it.toJson()) } })
}

private fun parseReasoningSummaryChunks(chunks: JSONArray?): List<ReasoningSummaryChunk> {
    if (chunks == null) return emptyList()
    return buildList {
        for (index in 0 until chunks.length()) {
            val chunk = chunks.optJSONObject(index) ?: continue
            add(
                ReasoningSummaryChunk(
                    id = chunk.optString("id").ifBlank { "reasoning-summary-$index" },
                    title = chunk.optString("title"),
                    detail = chunk.optString("detail"),
                    rawText = chunk.optString("rawText"),
                    isPending = chunk.optBoolean("isPending"),
                    createdAtMillis = chunk.optLong("createdAtMillis"),
                    timelineOrder = chunk.optLong("timelineOrder"),
                )
            )
        }
    }
}

private fun ReasoningSummaryChunk.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("detail", detail)
    put("rawText", if (title.isNotBlank() || detail.isNotBlank()) "" else rawText.take(PersistedReasoningRawTextMaxChars))
    put("isPending", isPending)
    put("createdAtMillis", createdAtMillis)
    put("timelineOrder", timelineOrder)
}

private fun parseToolInvocations(toolInvocations: JSONArray?): List<ChatToolInvocation> {
    if (toolInvocations == null) return emptyList()

    return buildList {
        for (toolIndex in 0 until toolInvocations.length()) {
            val toolInvocation = toolInvocations.optJSONObject(toolIndex) ?: continue
            add(
                ChatToolInvocation(
                    id = toolInvocation.optString("id").ifBlank { "tool-$toolIndex" },
                    toolName = toolInvocation.optString("toolName"),
                    argumentsJson = toolInvocation.optString("argumentsJson"),
                    outputJson = toolInvocation.optString("outputJson"),
                    isRunning = toolInvocation.optBoolean("isRunning"),
                    startedAtUptimeMillis = toolInvocation.optLong("startedAtUptimeMillis"),
                    completedAtUptimeMillis = if (toolInvocation.has("completedAtUptimeMillis")) {
                        toolInvocation.optLong("completedAtUptimeMillis")
                    } else {
                        null
                    },
                    startedAtMillis = toolInvocation.optLong("startedAtMillis"),
                    completedAtMillis = if (toolInvocation.has("completedAtMillis")) {
                        toolInvocation.optLong("completedAtMillis")
                    } else {
                        null
                    },
                    timelineOrder = toolInvocation.optLong("timelineOrder"),
                )
            )
        }
    }
}

private fun ChatToolInvocation.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("toolName", toolName)
    put("argumentsJson", argumentsJson)
    put("outputJson", truncatePersistedToolOutput(outputJson))
    put("isRunning", isRunning)
    put("startedAtUptimeMillis", startedAtUptimeMillis)
    completedAtUptimeMillis?.let { put("completedAtUptimeMillis", it) }
    put("startedAtMillis", startedAtMillis)
    completedAtMillis?.let { put("completedAtMillis", it) }
    put("timelineOrder", timelineOrder)
}

internal fun truncatePersistedToolOutput(outputJson: String): String {
    if (outputJson.length <= PersistedToolOutputMaxChars) return outputJson
    val kept = outputJson.take(PersistedToolOutputMaxChars)
    val omitted = outputJson.length - kept.length
    return "$kept\n\n[Aether 已截断 $omitted 个字符以节省存储]"
}

private fun parseStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}

private fun parseAgentTaskState(json: JSONObject?): AgentTaskState {
    if (json == null) return AgentTaskState()
    return AgentTaskState(
        goal = json.optString("goal").trim(),
        status = AgentTaskStatus.fromStorageValue(json.optString("status")),
        todos = parseAgentTaskItems(json.optJSONArray("todos")),
        completionCriteria = parseStringList(json.optJSONArray("completionCriteria")).ifEmpty {
            parseStringList(json.optJSONArray("completion_criteria"))
        },
        summary = json.optString("summary").trim(),
        updatedAtMillis = json.optLong("updatedAtMillis").takeIf { it > 0L }
            ?: json.optLong("updated_at_millis").takeIf { it > 0L }
            ?: 0L,
    )
}

private fun parseAgentTaskItems(array: JSONArray?): List<AgentTaskItem> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isBlank()) continue
            add(
                AgentTaskItem(
                    id = item.optString("id").trim().ifBlank { "task-$index" },
                    text = text,
                    done = item.optBoolean("done", item.optBoolean("completed", false)),
                )
            )
        }
    }
}

private fun AgentTaskState.toJson(): JSONObject = JSONObject().apply {
    put("goal", goal)
    put("status", status.storageValue)
    put("summary", summary)
    if (updatedAtMillis > 0L) {
        put("updatedAtMillis", updatedAtMillis)
    }
    put("todos", JSONArray().apply { todos.forEach { put(it.toJson()) } })
    put("completionCriteria", JSONArray().apply { completionCriteria.forEach(::put) })
}

private fun AgentTaskItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("text", text)
    put("done", done)
}

private fun timestampFromMessageId(messageId: String): Long {
    val timestamp = messageId.substringAfterLast('-', missingDelimiterValue = "")
    return timestamp.toLongOrNull()?.takeIf { it > 0L } ?: 0L
}
