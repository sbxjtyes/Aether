package com.zhousl.aether.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val IndexPollIntervalTradingMs = 5_000L
private const val IndexPollIntervalOffHoursMs = 60_000L
private const val SectorPollIntervalTradingMs = 10_000L
private const val WatchlistPollIntervalTradingMs = 5_000L
private const val SnapshotCacheMaxAgeMs = 5_000L

enum class MarketMonitorStartReason {
    UiVisible,
    EnabledAlerts,
    ManualRefresh,
}

class MarketMonitorRepository(
    private val scope: CoroutineScope,
    private val webToolsClient: WebToolsClient,
    private val watchlistRepository: WatchlistRepository,
    private val alertEngine: AlertEngine = AlertEngine(),
) {
    private val _snapshot = MutableStateFlow(MarketSnapshot())
    val snapshot: StateFlow<MarketSnapshot> = _snapshot.asStateFlow()

    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

    private val _alertEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents.asSharedFlow()

    private val activeReasons = mutableSetOf<MarketMonitorStartReason>()
    private var indexJob: Job? = null
    private var sectorJob: Job? = null
    private var watchlistJob: Job? = null
    private var sectorSort: SectorSort = SectorSort.ChangePercent
    private var sectorAscending: Boolean = false

    // 使用 AtomicBoolean 确保 start/stop 的读-改-写原子性，避免并发调用下重复启动。
    private val running = AtomicBoolean(false)

    fun start(reason: MarketMonitorStartReason = MarketMonitorStartReason.UiVisible) {
        synchronized(activeReasons) {
            activeReasons += reason
        }
        startJobsIfNeeded()
    }

    fun stop(reason: MarketMonitorStartReason = MarketMonitorStartReason.UiVisible) {
        val shouldStop = synchronized(activeReasons) {
            activeReasons -= reason
            activeReasons.isEmpty()
        }
        if (shouldStop) {
            stopAllJobs()
        }
    }

    fun stopAll() {
        synchronized(activeReasons) {
            activeReasons.clear()
        }
        stopAllJobs()
    }

    fun setSectorSort(
        sort: SectorSort,
        ascending: Boolean,
    ) {
        sectorSort = sort
        sectorAscending = ascending
        if (running.get()) {
            scope.launch(Dispatchers.IO) {
                refreshSectorsOnce()
            }
        }
    }

    fun cachedSnapshot(maxAgeMillis: Long = SnapshotCacheMaxAgeMs): MarketSnapshot? {
        val current = snapshot.value
        val age = System.currentTimeMillis() - current.updatedAtMillis
        return current.takeIf { current.updatedAtMillis > 0L && age in 0..maxAgeMillis }
    }

    private fun startJobsIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        _isRunningFlow.value = true
        indexJob = scope.launch(Dispatchers.IO) { pollIndices() }
        sectorJob = scope.launch(Dispatchers.IO) { pollSectors() }
        watchlistJob = scope.launch(Dispatchers.IO) { pollWatchlist() }
    }

    private fun stopAllJobs() {
        running.set(false)
        _isRunningFlow.value = false
        indexJob?.cancel()
        sectorJob?.cancel()
        watchlistJob?.cancel()
        indexJob = null
        sectorJob = null
        watchlistJob = null
    }

    val isRunning: Boolean get() = running.get()

    // ── 大盘指数轮询 ──────────────────────────────────────────────────────────

    private suspend fun pollIndices() {
        while (running.get()) {
            val isOpen = MarketTradingCalendar.isTrading()
            runCatching {
                val indices = webToolsClient.fetchMarketIndices().getOrThrow()
                val breadth = webToolsClient.fetchMarketBreadth().getOrDefault(_snapshot.value.breadth)
                _snapshot.value = _snapshot.value.copy(
                    indices = indices,
                    breadth = breadth,
                    isMarketOpen = isOpen,
                    updatedAtMillis = System.currentTimeMillis(),
                    lastError = "",
                )
            }.onFailure { t ->
                // CancellationException 必须重新抛出，让协程正常退出。
                if (t is CancellationException) throw t
                _snapshot.value = _snapshot.value.copy(lastError = t.message.orEmpty())
            }
            delay(if (isOpen) IndexPollIntervalTradingMs else IndexPollIntervalOffHoursMs)
        }
    }

    // ── 板块排行轮询 ──────────────────────────────────────────────────────────

    private suspend fun pollSectors() {
        while (running.get()) {
            if (MarketTradingCalendar.isTrading()) {
                runCatching {
                    refreshSectorsOnce()
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                }
                delay(SectorPollIntervalTradingMs)
            } else {
                delay(IndexPollIntervalOffHoursMs)
            }
        }
    }

    // ── 自选股轮询 ────────────────────────────────────────────────────────────

    private suspend fun pollWatchlist() {
        while (running.get()) {
            val entries = watchlistRepository.entries.first()
            if (entries.isNotEmpty() && MarketTradingCalendar.isTrading()) {
                val symbols = entries.map { it.symbol }
                runCatching {
                    val quotes = webToolsClient.fetchWatchlistQuotes(symbols).getOrThrow()
                    _snapshot.value = _snapshot.value.copy(watchlistQuotes = quotes)
                    val events = alertEngine.checkAlerts(entries, quotes)
                    events.forEach { event ->
                        _alertEvents.tryEmit(event)
                        watchlistRepository.updateAlertRuleLastTriggered(
                            ruleId = event.rule.id,
                            triggeredAtMillis = event.triggeredAtMillis,
                        )
                    }
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                }
                delay(WatchlistPollIntervalTradingMs)
            } else {
                delay(IndexPollIntervalOffHoursMs)
            }
        }
    }

    private suspend fun refreshSectorsOnce() {
        val sort = sectorSort
        val ascending = sectorAscending
        val industry = webToolsClient.fetchSectorRank(
            type = SectorType.Industry,
            sort = sort,
            ascending = ascending,
        ).getOrThrow()
        val concept = webToolsClient.fetchSectorRank(
            type = SectorType.Concept,
            sort = sort,
            ascending = ascending,
        ).getOrThrow()
        _snapshot.value = _snapshot.value.copy(
            industrySectors = industry,
            conceptSectors = concept,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    /** 手动刷新一次快照（供 AI 工具调用） */
    suspend fun refreshOnce(): MarketSnapshot {
        start(MarketMonitorStartReason.ManualRefresh)
        val entries = watchlistRepository.entries.first()
        val symbols = entries.map { it.symbol }
        val indices = runCatching { webToolsClient.fetchMarketIndices().getOrThrow() }.getOrDefault(emptyList())
        val breadth = runCatching { webToolsClient.fetchMarketBreadth().getOrThrow() }.getOrDefault(MarketBreadth())
        val industry = runCatching {
            webToolsClient.fetchSectorRank(SectorType.Industry, sort = sectorSort, ascending = sectorAscending).getOrThrow()
        }.getOrDefault(emptyList())
        val concept = runCatching {
            webToolsClient.fetchSectorRank(SectorType.Concept, sort = sectorSort, ascending = sectorAscending).getOrThrow()
        }.getOrDefault(emptyList())
        val quotes = if (symbols.isNotEmpty()) {
            runCatching { webToolsClient.fetchWatchlistQuotes(symbols).getOrThrow() }.getOrDefault(emptyList())
        } else emptyList()

        val updated = MarketSnapshot(
            indices = indices,
            breadth = breadth,
            industrySectors = industry,
            conceptSectors = concept,
            watchlistQuotes = quotes,
            updatedAtMillis = System.currentTimeMillis(),
            isMarketOpen = MarketTradingCalendar.isTrading(),
        )
        _snapshot.value = updated
        stop(MarketMonitorStartReason.ManualRefresh)
        return updated
    }
}
