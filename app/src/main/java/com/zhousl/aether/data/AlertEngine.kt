package com.zhousl.aether.data

/** 预警引擎：对每次行情快照检查所有预警规则，返回本次触发的事件列表。*/
class AlertEngine {

    fun checkAlerts(
        entries: List<WatchlistEntry>,
        quotes: List<WatchlistQuote>,
    ): List<AlertEvent> {
        val now = System.currentTimeMillis()
        val quoteMap = quotes.associateBy { it.symbol }
        return entries.flatMap { entry ->
            val quote = quoteMap[entry.symbol] ?: return@flatMap emptyList()
            entry.alertRules
                .filter { rule ->
                    rule.enabled &&
                        now - rule.lastTriggeredAtMillis > rule.cooldownMinutes * 60_000L &&
                        isTriggered(rule, quote)
                }
                .map { rule -> AlertEvent(rule = rule, quote = quote, triggeredAtMillis = now) }
        }
    }

    private fun isTriggered(rule: AlertRule, quote: WatchlistQuote): Boolean = when (rule.type) {
        AlertType.PriceAbove -> quote.price > rule.threshold
        AlertType.PriceBelow -> quote.price < rule.threshold && quote.price > 0
        AlertType.ChangePercentUp -> quote.changePercent > rule.threshold
        AlertType.ChangePercentDown -> quote.changePercent < -kotlin.math.abs(rule.threshold)
    }
}
