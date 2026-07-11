package com.zhousl.aether.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhousl.aether.AetherForegroundService
import com.zhousl.aether.aetherRuntime
import com.zhousl.aether.data.AlertRule
import com.zhousl.aether.data.AlertType
import com.zhousl.aether.data.MarketMonitorStartReason
import com.zhousl.aether.data.MarketSnapshot
import com.zhousl.aether.data.SectorSort
import com.zhousl.aether.data.SectorType
import com.zhousl.aether.data.WatchlistEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class MarketMonitorUiState(
    val snapshot: MarketSnapshot = MarketSnapshot(),
    val watchlistEntries: List<WatchlistEntry> = emptyList(),
    val selectedSectorType: SectorType = SectorType.Industry,
    val isMonitoring: Boolean = false,
)

class MarketMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = application.aetherRuntime
    private val monitor = runtime.marketMonitorRepository
    private val watchlist = runtime.watchlistRepository
    private val webToolsClient = runtime.webToolsClient

    val uiState: StateFlow<MarketMonitorUiState> = combine(
        monitor.snapshot,
        watchlist.entries,
    ) { snapshot, entries ->
        MarketMonitorUiState(
            snapshot = snapshot,
            watchlistEntries = entries,
            // 使用 lambda 入参 snapshot 而非 monitor.snapshot.value（避免读取滞后的旧值）。
            isMonitoring = snapshot.indices.isNotEmpty() || monitor.isRunning,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MarketMonitorUiState(),
    )

    fun startMonitoring() {
        monitor.start(MarketMonitorStartReason.UiVisible)
        // 监控运行时保持前台服务存活，防止系统在后台杀掉进程后台网络访问被限制。
        try {
            AetherForegroundService.ensureRunning(getApplication())
        } catch (_: Throwable) {
            // 前台服务启动失败不阻断监控（App 在前台时轮询依然有效）。
        }
    }

    fun stopMonitoring() {
        monitor.stop(MarketMonitorStartReason.UiVisible)
    }

    fun updateSectorSort(
        sort: SectorSort,
        ascending: Boolean,
    ) {
        monitor.setSectorSort(sort, ascending)
    }

    fun addToWatchlist(symbol: String, name: String) {
        viewModelScope.launch {
            val normalizedSymbol = symbol.uppercase()
            val resolvedName = name.ifBlank { lookupStockName(normalizedSymbol) }
            watchlist.addEntry(normalizedSymbol, resolvedName)
        }
    }

    fun removeFromWatchlist(symbol: String) {
        viewModelScope.launch {
            watchlist.removeEntry(symbol)
        }
    }

    fun addAlertRule(
        symbol: String,
        name: String,
        type: AlertType,
        threshold: Double,
        cooldownMinutes: Int = 5,
    ) {
        viewModelScope.launch {
            val rule = AlertRule(
                id = UUID.randomUUID().toString(),
                symbol = symbol,
                name = name,
                type = type,
                threshold = threshold,
                cooldownMinutes = cooldownMinutes,
            )
            watchlist.addAlertRule(symbol, rule)
        }
    }

    fun removeAlertRule(symbol: String, ruleId: String) {
        viewModelScope.launch {
            watchlist.removeAlertRule(symbol, ruleId)
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            monitor.refreshOnce()
        }
    }

    private suspend fun lookupStockName(symbolOrQuery: String): String {
        return webToolsClient.searchStocks(
            com.zhousl.aether.data.StockSearchRequest(
                query = symbolOrQuery,
                maxResults = 1,
            )
        ).mapCatching { json ->
            json.optJSONObject("QuotationCodeTable")
                ?.optJSONArray("Data")
                ?.optJSONObject(0)
                ?.optString("Name")
                .orEmpty()
        }.getOrDefault("")
    }
}
