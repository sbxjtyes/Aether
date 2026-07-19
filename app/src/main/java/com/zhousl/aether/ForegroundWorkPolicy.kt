package com.zhousl.aether

internal fun shouldKeepAetherForeground(
    activeTaskCount: Int,
    keepTasksRunningInBackground: Boolean,
    isMarketMonitoring: Boolean,
): Boolean =
    (activeTaskCount > 0 && keepTasksRunningInBackground) ||
        isMarketMonitoring
