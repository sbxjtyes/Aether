package com.zhousl.aether

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class AetherForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hasEnteredForeground = false
    private var lastForegroundUpdateMillis = 0L
    private var lastForegroundSignature = ""

    override fun onCreate() {
        super.onCreate()
        startRequested.set(true)
        val runtime = aetherRuntime
        enterForeground(
            runtime.notificationController.buildForegroundNotification(
                sessions = emptyList(),
                executionStates = emptyMap(),
                isMarketMonitoring = runtime.marketMonitorRepository.isRunning,
            ),
        )
        serviceScope.launch {
            combine(
                runtime.chatStateStore.state,
                runtime.sessionExecutionManager.executionStates,
                runtime.settingsRepository.settings,
                runtime.marketMonitorRepository.isRunningFlow,
            ) { chatState, executionStates, settings, isMonitoring ->
                ForegroundState(chatState.sessions, executionStates, settings, isMonitoring)
            }.conflate().collect { state ->
                val sessions = state.sessions
                val executionStates = state.executionStates
                val settings = state.settings
                val activeCount = executionStates.values.count { it.isRunning }
                val isMonitoring = state.isMarketMonitoring
                if ((activeCount == 0 && !isMonitoring) || (!settings.keepTasksRunningInBackground && !isMonitoring)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val signature = foregroundNotificationSignature(sessions, executionStates, isMonitoring)
                    val now = System.currentTimeMillis()
                    if (
                        signature != lastForegroundSignature ||
                        now - lastForegroundUpdateMillis >= ForegroundNotificationMinIntervalMillis
                    ) {
                        enterForeground(
                            runtime.notificationController.buildForegroundNotification(
                                sessions = sessions,
                                executionStates = executionStates,
                                isMarketMonitoring = isMonitoring,
                            ),
                        )
                        lastForegroundSignature = signature
                        lastForegroundUpdateMillis = now
                    } else {
                        delay(ForegroundNotificationMinIntervalMillis - (now - lastForegroundUpdateMillis))
                    }
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startRequested.set(true)
        if (!hasEnteredForeground) {
            val runtime = aetherRuntime
            enterForeground(
                runtime.notificationController.buildForegroundNotification(
                    sessions = emptyList(),
                    executionStates = emptyMap(),
                    isMarketMonitoring = runtime.marketMonitorRepository.isRunning,
                ),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        startRequested.set(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            ForegroundNotificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        hasEnteredForeground = true
    }

    private fun foregroundNotificationSignature(
        sessions: List<com.zhousl.aether.ui.ChatSession>,
        executionStates: Map<String, com.zhousl.aether.data.SessionExecutionState>,
        isMarketMonitoring: Boolean,
    ): String {
        val activeIds = executionStates
            .filterValues { it.isRunning }
            .keys
            .sorted()
        val activeTitles = activeIds.joinToString(separator = "|") { sessionId ->
            sessions.firstOrNull { it.id == sessionId }?.title.orEmpty()
        }
        return "${activeIds.size}:$activeTitles:$isMarketMonitoring"
    }

    companion object {
        private const val ForegroundNotificationMinIntervalMillis = 1_500L

        private val startRequested = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (!startRequested.compareAndSet(false, true)) return
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, AetherForegroundService::class.java),
                )
            } catch (t: Throwable) {
                startRequested.set(false)
                throw t
            }
        }
    }
}

private data class ForegroundState(
    val sessions: List<com.zhousl.aether.ui.ChatSession>,
    val executionStates: Map<String, com.zhousl.aether.data.SessionExecutionState>,
    val settings: com.zhousl.aether.data.AppSettings,
    val isMarketMonitoring: Boolean,
)
