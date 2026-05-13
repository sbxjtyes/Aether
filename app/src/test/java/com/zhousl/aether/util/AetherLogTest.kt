package com.zhousl.aether.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherLogTest {
    /**
     * 验证导出日志会脱敏常见凭据和完整 URI。
     */
    @Test
    fun sanitizeForExportRedactsCredentialsAndUris() {
        val raw = "api_key=sk-test Authorization: Bearer abc.def token=secret content://media/external/file/42"

        val sanitized = AetherLog.sanitizeForExport(raw)

        assertFalse(sanitized.contains("sk-test"))
        assertFalse(sanitized.contains("abc.def"))
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("content://media/external/file/42"))
        assertTrue(sanitized.contains("api_key=<redacted>"))
        assertTrue(sanitized.contains("Authorization=<redacted>"))
        assertTrue(sanitized.contains("token=<redacted>"))
    }

    /**
     * 验证 Android 绝对路径会被摘要化，避免导出真实目录。
     */
    @Test
    fun sanitizeForExportSummarizesAndroidAbsolutePaths() {
        val raw = "failed path=/storage/emulated/0/Documents/private/report.txt"

        val sanitized = AetherLog.sanitizeForExport(raw)

        assertFalse(sanitized.contains("/storage/emulated/0/Documents/private/report.txt"))
        assertTrue(sanitized.contains(".../report.txt(depth=6)"))
    }

    /**
     * 验证写入 logcat 前会进行长度裁剪，避免超长日志影响系统日志。
     */
    @Test
    fun sanitizeForLogcatTruncatesLongMessages() {
        val sanitized = AetherLog.sanitizeForLogcat("-".repeat(4_100))

        assertEquals(4_012, sanitized.length)
        assertTrue(sanitized.endsWith("…<truncated>"))
    }

    /**
     * 验证 URI 摘要只保留 scheme，不暴露路径和查询参数。
     */
    @Test
    fun summarizeUriKeepsOnlyScheme() {
        val summary = AetherLog.summarizeUri("file:///storage/emulated/0/Download/secret.txt?token=abc")

        assertEquals("file://<redacted-uri>", summary)
    }

    /**
     * 验证路径摘要只保留文件名和路径深度。
     */
    @Test
    fun summarizePathKeepsFileNameAndDepthOnly() {
        val summary = AetherLog.summarizePath("/data/user/0/com.example/files/session/log.txt")

        assertEquals(".../log.txt(depth=7)", summary)
    }
}
