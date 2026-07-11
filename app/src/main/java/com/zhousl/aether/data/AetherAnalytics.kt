package com.zhousl.aether.data

/** PostHog 埋点已移除；保留空壳避免修改全部调用点，所有方法均为无操作。 */
object AetherAnalytics {
    fun capture(
        event: String,
        properties: Map<String, Any> = emptyMap(),
    ) = Unit

    fun captureException(
        throwable: Throwable,
        properties: Map<String, Any> = emptyMap(),
    ) = Unit
}
