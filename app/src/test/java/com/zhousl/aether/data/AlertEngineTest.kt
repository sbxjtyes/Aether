package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    @Test
    fun checkAlertsTriggersPriceAndPercentRules() {
        val quote = quote(price = 12.5, changePercent = 6.2)
        val entries = listOf(
            WatchlistEntry(
                symbol = "600000.SH",
                name = "测试",
                alertRules = listOf(
                    rule("above", AlertType.PriceAbove, 12.0),
                    rule("below", AlertType.PriceBelow, 10.0),
                    rule("up", AlertType.ChangePercentUp, 5.0),
                    rule("down", AlertType.ChangePercentDown, 5.0),
                ),
            )
        )

        val events = AlertEngine().checkAlerts(entries, listOf(quote))

        assertEquals(listOf("above", "up"), events.map { it.rule.id })
    }

    @Test
    fun checkAlertsHonorsDisabledRulesAndCooldown() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            WatchlistEntry(
                symbol = "600000.SH",
                name = "测试",
                alertRules = listOf(
                    rule("disabled", AlertType.PriceAbove, 10.0, enabled = false),
                    rule("cooling", AlertType.PriceAbove, 10.0, lastTriggeredAtMillis = now),
                    rule("ready", AlertType.PriceAbove, 10.0, lastTriggeredAtMillis = now - 6 * 60_000L),
                ),
            )
        )

        val events = AlertEngine().checkAlerts(entries, listOf(quote(price = 11.0)))

        assertEquals(1, events.size)
        assertEquals("ready", events.first().rule.id)
        assertTrue(events.first().triggeredAtMillis >= now)
    }

    @Test
    fun checkAlertsTriggersDownsideRulesWithAbsoluteThreshold() {
        val entries = listOf(
            WatchlistEntry(
                symbol = "600000.SH",
                name = "测试",
                alertRules = listOf(
                    rule("priceBelow", AlertType.PriceBelow, 9.5),
                    rule("changeDown", AlertType.ChangePercentDown, 4.0),
                ),
            )
        )

        val events = AlertEngine().checkAlerts(
            entries,
            listOf(quote(price = 9.0, changePercent = -4.5)),
        )

        assertEquals(listOf("priceBelow", "changeDown"), events.map { it.rule.id })
    }

    private fun rule(
        id: String,
        type: AlertType,
        threshold: Double,
        enabled: Boolean = true,
        lastTriggeredAtMillis: Long = 0L,
    ): AlertRule = AlertRule(
        id = id,
        symbol = "600000.SH",
        name = "测试",
        type = type,
        threshold = threshold,
        enabled = enabled,
        cooldownMinutes = 5,
        lastTriggeredAtMillis = lastTriggeredAtMillis,
    )

    private fun quote(
        price: Double,
        changePercent: Double = 0.0,
    ): WatchlistQuote = WatchlistQuote(
        symbol = "600000.SH",
        name = "测试",
        price = price,
        change = 0.0,
        changePercent = changePercent,
    )
}
