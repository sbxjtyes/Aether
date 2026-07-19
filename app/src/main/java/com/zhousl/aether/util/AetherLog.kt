package com.zhousl.aether.util

import android.util.Log
import com.zhousl.aether.BuildConfig

private const val RedactedValue = "<redacted>"
private const val RedactedUri = "<redacted-uri>"
private const val MaxLogMessageLength = 4_000
private const val RecentLogCapacity = 300

object AetherLog {
    private val sensitiveAssignmentRegex = Regex(
        pattern = "(?i)\\b(api[_-]?key|authorization|bearer|token|password|secret|access[_-]?key|refresh[_-]?token)\\b\\s*[:=]\\s*([^\\s,;)}]+)",
    )
    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val uriRegex = Regex("(?i)\\b(content|file)://[^\\s,;)}]+")
    private val androidAbsolutePathRegex = Regex("(?i)(/storage/emulated/\\d+|/sdcard|/data/user/\\d+|/data/data|/mnt/media_rw|/cache)/[^\\s,;)}]+")
    private val longBase64LikeRegex = Regex("\\b[A-Za-z0-9+/]{48,}={0,2}\\b")
    private val recentLogLock = Any()
    private val recentLogs = ArrayDeque<RecentLogEntry>()

    enum class Level {
        Debug,
        Info,
        Warn,
        Error,
    }

    data class RecentLogEntry(
        val timestampMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    /**
     * 输出调试级日志，仅在 Debug 构建中启用，避免 Release 暴露细节。
     */
    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        write(Level.Debug, tag, message, throwable)
    }

    /**
     * 输出信息级日志，仅在 Debug 构建中启用，适合诊断流程状态。
     */
    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        write(Level.Info, tag, message, throwable)
    }

    /**
     * 输出警告级日志，在所有构建类型中保留但统一脱敏。
     */
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        write(Level.Warn, tag, message, throwable)
    }

    /**
     * 输出错误级日志，在所有构建类型中保留但统一脱敏。
     */
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        write(Level.Error, tag, message, throwable)
    }

    /**
     * 输出结构化事件日志，统一格式化字段并交由日志核心处理。
     */
    fun event(
        tag: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        level: Level = Level.Info,
        throwable: Throwable? = null,
    ) {
        val message = buildString {
            append("event=")
            append(event)
            fields.entries
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    append(' ')
                    append(key)
                    append('=')
                    append(value?.toString().orEmpty())
                }
        }
        write(level, tag, message, throwable)
    }

    /**
     * 返回应用内最近日志快照，供诊断导出在 logcat 不可用时兜底。
     */
    fun recentLogsForExport(): String {
        val snapshot = synchronized(recentLogLock) { recentLogs.toList() }
        if (snapshot.isEmpty()) return "No in-memory app logs captured."
        return snapshot.joinToString(separator = "\n") { entry ->
            "${entry.timestampMillis} ${entry.level.name.uppercase()} ${entry.tag}: ${entry.message}"
        }
    }

    /**
     * 清空应用内日志缓冲，供测试或显式诊断重置场景使用。
     */
    fun clearRecentLogs() {
        synchronized(recentLogLock) {
            recentLogs.clear()
        }
    }

    /**
     * 统一处理日志脱敏、内存缓冲与系统 logcat 输出。
     */
    private fun write(
        level: Level,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val sanitizedMessage = buildSanitizedExportMessage(message, throwable)
        appendRecentLog(
            RecentLogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                tag = sanitizeForLogcat(tag),
                message = sanitizedMessage,
            )
        )
        if (!shouldWriteToLogcat(level)) return

        val logcatMessage = sanitizeForLogcat(sanitizedMessage)
        runCatching {
            when (level) {
                Level.Debug -> Log.d(tag, logcatMessage)
                Level.Info -> Log.i(tag, logcatMessage)
                Level.Warn -> Log.w(tag, logcatMessage)
                Level.Error -> Log.e(tag, logcatMessage)
            }
        }
    }

    /**
     * 判断当前等级是否应写入系统 logcat。
     */
    private fun shouldWriteToLogcat(level: Level): Boolean =
        BuildConfig.DEBUG || level == Level.Warn || level == Level.Error

    /**
     * 写入内存环形日志缓冲，超过容量时丢弃最旧记录。
     */
    private fun appendRecentLog(entry: RecentLogEntry) {
        synchronized(recentLogLock) {
            while (recentLogs.size >= RecentLogCapacity) {
                recentLogs.removeFirst()
            }
            recentLogs.addLast(entry)
        }
    }

    /**
     * 合并日志正文与异常堆栈，并在进入缓冲或导出前统一脱敏。
     */
    private fun buildSanitizedExportMessage(
        message: String,
        throwable: Throwable?,
    ): String {
        val raw = if (throwable == null) {
            message
        } else {
            message + "\n" + throwable.stackTraceToString()
        }
        return sanitizeForExport(raw)
    }

    /**
     * 对待写入 logcat 的文本进行敏感信息脱敏和长度裁剪。
     */
    fun sanitizeForLogcat(raw: String): String =
        sanitize(raw).let { sanitized ->
            if (sanitized.length <= MaxLogMessageLength) {
                sanitized
            } else {
                sanitized.take(MaxLogMessageLength) + "…<truncated>"
            }
        }

    /**
     * 对导出诊断日志的文本进行完整脱敏，不进行长度裁剪。
     */
    fun sanitizeForExport(raw: String): String = sanitize(raw)

    /**
     * 对原始日志文本进行敏感信息脱敏。
     */
    private fun sanitize(raw: String): String = raw
        .replace(bearerRegex, "Bearer $RedactedValue")
        .replace(sensitiveAssignmentRegex) { result ->
            "${result.groupValues[1]}=$RedactedValue"
        }
        .replace(uriRegex, RedactedUri)
        .replace(androidAbsolutePathRegex) { result ->
            summarizePath(result.value)
        }
        .replace(longBase64LikeRegex, RedactedValue)

    /**
     * 生成适合日志输出的 URI 摘要，保留 scheme 并隐藏具体定位信息。
     */
    fun summarizeUri(rawUri: String): String {
        val scheme = rawUri.substringBefore("://", missingDelimiterValue = "").ifBlank { "uri" }
        return "$scheme://$RedactedUri"
    }

    /**
     * 生成适合日志输出的路径摘要，仅保留文件名与路径深度，避免泄露完整目录。
     */
    fun summarizePath(path: String): String {
        val normalized = path.replace('\\', '/').trim()
        val fileName = normalized.substringAfterLast('/').ifBlank { RedactedValue }
        val depth = normalized.split('/').count { it.isNotBlank() }
        return ".../$fileName(depth=$depth)"
    }
}
