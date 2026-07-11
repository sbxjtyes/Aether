package com.zhousl.aether

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.zhousl.aether.data.AgentExtensionsRepository
import com.zhousl.aether.data.AgentModeController
import com.zhousl.aether.data.AgentSkillManager
import com.zhousl.aether.data.AlertEngine
import com.zhousl.aether.data.ChatRepository
import com.zhousl.aether.data.MarketMonitorRepository
import com.zhousl.aether.data.MarketMonitorStartReason
import com.zhousl.aether.data.RootSetupController
import com.zhousl.aether.data.ChatStateStore
import com.zhousl.aether.data.SessionExecutionManager
import com.zhousl.aether.data.SettingsRepository
import com.zhousl.aether.data.WatchlistRepository
import com.zhousl.aether.data.WebToolsClient
import com.zhousl.aether.data.WorkspaceFileBridge
import com.zhousl.aether.termux.TermuxBashTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AetherApplication : Application() {
    val runtime: AetherAppRuntime by lazy(LazyThreadSafetyMode.NONE) {
        AetherAppRuntime(this)
    }

    override fun onCreate() {
        super.onCreate()
        runtime.initialize()
    }
}

class AetherAppRuntime(
    private val application: AetherApplication,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(application)
    val chatRepository = ChatRepository(application, appScope)
    val extensionsRepository = AgentExtensionsRepository(application)
    val bashTool = TermuxBashTool(application)
    val rootSetupController = RootSetupController(
        context = application,
        bashTool = bashTool,
    )
    val workspaceFileBridge = WorkspaceFileBridge(
        context = application,
        bashTool = bashTool,
    )
    val agentModeController = AgentModeController(
        context = application,
        bashTool = bashTool,
        workspaceFileBridge = workspaceFileBridge,
    )
    val skillManager = AgentSkillManager(
        context = application,
        extensionsRepository = extensionsRepository,
    )
    val webToolsClient = WebToolsClient()
    val watchlistRepository = WatchlistRepository(application)
    val marketMonitorRepository = MarketMonitorRepository(
        scope = appScope,
        webToolsClient = webToolsClient,
        watchlistRepository = watchlistRepository,
        alertEngine = AlertEngine(),
    )
    val appForegroundTracker = AppForegroundTracker()
    val notificationController = AetherNotificationController(application)
    val chatStateStore = ChatStateStore(
        scope = appScope,
        chatRepository = chatRepository,
    )
    val sessionExecutionManager = SessionExecutionManager(
        application = application,
        scope = appScope,
        settingsRepository = settingsRepository,
        extensionsRepository = extensionsRepository,
        chatStateStore = chatStateStore,
        bashTool = bashTool,
        workspaceFileBridge = workspaceFileBridge,
        agentModeController = agentModeController,
        skillManager = skillManager,
        webToolsClient = webToolsClient,
        marketMonitorRepository = marketMonitorRepository,
        notificationController = notificationController,
        appForegroundTracker = appForegroundTracker,
    )

    fun initialize() {
        notificationController.ensureChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundTracker)
        // 监听预警事件并发送通知
        appScope.launch {
            marketMonitorRepository.alertEvents.collect { event ->
                notificationController.notifyMarketAlert(event)
            }
        }
        appScope.launch {
            watchlistRepository.entries.collect { entries ->
                val hasEnabledAlerts = entries.any { entry -> entry.alertRules.any { it.enabled } }
                if (hasEnabledAlerts) {
                    marketMonitorRepository.start(MarketMonitorStartReason.EnabledAlerts)
                    runCatching { AetherForegroundService.ensureRunning(application) }
                } else {
                    marketMonitorRepository.stop(MarketMonitorStartReason.EnabledAlerts)
                }
            }
        }
    }

    @Suppress("unused")
    fun initializePostHog() = Unit
}

class AppForegroundTracker : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}

val Context.aetherRuntime: AetherAppRuntime
    get() = (applicationContext as AetherApplication).runtime
