package com.zhousl.aether.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 市场指数快照（上证 / 深证 / 创业板 / 科创板 / 北交所）*/
data class MarketIndex(
    val code: String,
    val name: String,
    val secid: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long = 0L,
    val amount: Double = 0.0,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/** 板块行情条目 */
data class SectorItem(
    val code: String,
    val name: String,
    val changePercent: Double,
    val leadingStock: String = "",
    val leadingStockChange: Double = 0.0,
    val turnoverRate: Double = 0.0,
    val amount: Double = 0.0,
)

/** 板块类型 */
enum class SectorType { Industry, Concept }

/** 板块排序字段 */
enum class SectorSort {
    ChangePercent,
    TurnoverRate,
}

/** 市场情绪（涨跌统计）*/
data class MarketBreadth(
    val risingCount: Int = 0,
    val fallingCount: Int = 0,
    val flatCount: Int = 0,
    val limitUpCount: Int = 0,
    val limitDownCount: Int = 0,
    val isEstimatedLimitStats: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/** 自选股实时报价 */
data class WatchlistQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long = 0L,
    val amount: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val open: Double = 0.0,
    val preClose: Double = 0.0,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/** 预警类型 */
enum class AlertType {
    PriceAbove,
    PriceBelow,
    ChangePercentUp,
    ChangePercentDown,
}

/** 预警规则 */
data class AlertRule(
    val id: String,
    val symbol: String,
    val name: String,
    val type: AlertType,
    val threshold: Double,
    val enabled: Boolean = true,
    val cooldownMinutes: Int = 5,
    val lastTriggeredAtMillis: Long = 0L,
)

/** 自选股条目 */
data class WatchlistEntry(
    val symbol: String,
    val name: String,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val alertRules: List<AlertRule> = emptyList(),
)

/** 预警触发事件 */
data class AlertEvent(
    val rule: AlertRule,
    val quote: WatchlistQuote,
    val triggeredAtMillis: Long = System.currentTimeMillis(),
)

/** 完整盘面快照 */
data class MarketSnapshot(
    val indices: List<MarketIndex> = emptyList(),
    val breadth: MarketBreadth = MarketBreadth(),
    val industrySectors: List<SectorItem> = emptyList(),
    val conceptSectors: List<SectorItem> = emptyList(),
    val watchlistQuotes: List<WatchlistQuote> = emptyList(),
    val isLoading: Boolean = false,
    val lastError: String = "",
    val updatedAtMillis: Long = 0L,
    val isMarketOpen: Boolean = false,
)

enum class TradingSessionState {
    Trading,
    OffHours,
    Holiday,
}

object MarketTradingCalendar {
    private val chinaZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val tradingStart: LocalTime = LocalTime.of(9, 25)
    private val tradingEnd: LocalTime = LocalTime.of(15, 0)

    private val knownHolidays: Set<LocalDate> = setOf(
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 2),
        LocalDate.of(2026, 2, 16),
        LocalDate.of(2026, 2, 17),
        LocalDate.of(2026, 2, 18),
        LocalDate.of(2026, 2, 19),
        LocalDate.of(2026, 2, 20),
        LocalDate.of(2026, 2, 23),
        LocalDate.of(2026, 4, 6),
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 4),
        LocalDate.of(2026, 5, 5),
        LocalDate.of(2026, 6, 19),
        LocalDate.of(2026, 9, 25),
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 2),
        LocalDate.of(2026, 10, 5),
        LocalDate.of(2026, 10, 6),
        LocalDate.of(2026, 10, 7),
    )

    fun state(now: ZonedDateTime = ZonedDateTime.now(chinaZone)): TradingSessionState {
        val local = now.withZoneSameInstant(chinaZone)
        if (isHoliday(local.toLocalDate())) return TradingSessionState.Holiday
        val time = local.toLocalTime()
        return if (!time.isBefore(tradingStart) && !time.isAfter(tradingEnd)) {
            TradingSessionState.Trading
        } else {
            TradingSessionState.OffHours
        }
    }

    fun isTrading(now: ZonedDateTime = ZonedDateTime.now(chinaZone)): Boolean =
        state(now) == TradingSessionState.Trading

    fun isHoliday(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY ||
            date.dayOfWeek == DayOfWeek.SUNDAY ||
            knownHolidays.contains(date)
}

/** 判断当前是否在交易时段（A 股交易日 09:25-15:00 北京时间）*/
fun isMarketTradingHours(): Boolean {
    return MarketTradingCalendar.isTrading()
}

/** A 股五大指数的 secid 列表 */
val MainMarketIndexSecids = listOf(
    "1.000001",  // 上证指数
    "0.399001",  // 深证成指
    "0.399006",  // 创业板指
    "1.000688",  // 科创50
    "0.899050",  // 北证50
)
