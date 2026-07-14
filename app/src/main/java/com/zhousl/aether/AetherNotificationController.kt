package com.zhousl.aether

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zhousl.aether.data.AlertEvent
import com.zhousl.aether.data.AlertType
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.ui.ChatSession

private const val ForegroundChannelId = "aether_background_runs"
private const val CompletionChannelId = "aether_completed_runs"
private const val MarketAlertChannelId = "aether_market_alerts"
const val ForegroundNotificationId = 1001
private const val MarketAlertNotificationBaseId = 2000

class AetherNotificationController(
    private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val foregroundChannel = NotificationChannel(
            ForegroundChannelId,
            "Background tasks",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active Aether sessions running in the background."
            setShowBadge(false)
        }
        val completionChannel = NotificationChannel(
            CompletionChannelId,
            "Task completion",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts you when a background Aether session finishes."
        }
        val alertChannel = NotificationChannel(
            MarketAlertChannelId,
            "行情预警",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "股价突破、涨跌幅超限等实时预警提醒。"
        }
        manager.createNotificationChannel(foregroundChannel)
        manager.createNotificationChannel(completionChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun buildForegroundNotification(
        sessions: List<ChatSession>,
        executionStates: Map<String, SessionExecutionState>,
        isMarketMonitoring: Boolean = false,
    ): Notification {
        val activeSessions = sessions.filter { executionStates[it.id]?.isRunning == true }
        val title = when {
            activeSessions.isNotEmpty() && isMarketMonitoring -> "Aether is running tasks and market alerts"
            activeSessions.size == 1 -> "Aether is running 1 task"
            activeSessions.size > 1 -> "Aether is running ${activeSessions.size} tasks"
            isMarketMonitoring -> "Aether is monitoring market alerts"
            else -> "Aether is running"
        }
        val body = buildList {
            activeSessions.take(3).forEach { add(it.title.ifBlank { "Untitled chat" }) }
            if (isMarketMonitoring) add("行情预警监控中")
        }.joinToString(separator = ", ")
            .ifBlank { "Keeping active sessions alive in the background." }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        return NotificationCompat.Builder(context, ForegroundChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildList {
                            activeSessions.forEach { session ->
                                add("- ${session.title.ifBlank { "Untitled chat" }}")
                            }
                            if (isMarketMonitoring) add("- 行情预警监控中")
                        }.joinToString(separator = "\n").ifBlank { body }
                    )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    fun notifyCompletion(
        sessionId: String,
        sessionTitle: String,
        summary: String,
        failed: Boolean,
    ) {
        if (!canPostUserNotifications()) return

        val contentIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        val title = if (failed) {
            "Aether task finished with an issue"
        } else {
            "Aether task finished"
        }

        val notification = NotificationCompat.Builder(context, CompletionChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(sessionTitle.ifBlank { "Untitled chat" })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(sessionTitle.ifBlank { "Untitled chat" })
                        if (summary.isNotBlank()) {
                            append("\n")
                            append(summary)
                        }
                    }
                )
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            notificationManager.notify(sessionId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the preflight check.
        }
    }

    fun notifyMarketAlert(event: AlertEvent) {
        if (!canPostUserNotifications()) return
        val quote = event.quote
        val rule = event.rule
        val title = buildString {
            append(quote.name.ifBlank { quote.symbol })
            append(" · ")
            append(when (rule.type) {
                AlertType.PriceAbove -> "价格突破 ${rule.threshold}"
                AlertType.PriceBelow -> "价格跌破 ${rule.threshold}"
                AlertType.ChangePercentUp -> "涨幅超 ${rule.threshold}%"
                AlertType.ChangePercentDown -> "跌幅超 ${rule.threshold}%"
            })
        }
        val body = buildString {
            val sign = if (quote.change >= 0) "+" else ""
            append("当前价：${String.format(java.util.Locale.US, "%.2f", quote.price)}")
            append("  $sign${String.format(java.util.Locale.US, "%.2f", quote.changePercent)}%")
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            rule.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("market_alert_symbol", quote.symbol)
                putExtra("market_alert_rule_id", rule.id)
                putExtra("market_alert_auto_analyze", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )
        val notification = NotificationCompat.Builder(context, MarketAlertChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            // 取 abs 避免 hashCode 为负数时 ID 变负（部分系统对负数 ID 行为未定义）。
            val notifId = MarketAlertNotificationBaseId + (rule.id.hashCode() and Int.MAX_VALUE) % 10_000
            notificationManager.notify(notifId, notification)
        } catch (_: SecurityException) {}
    }

    private fun canPostUserNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntentMutabilityFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
