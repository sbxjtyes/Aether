package com.zhousl.aether.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketTradingCalendarTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun stateReturnsTradingDuringTradingWindowOnBusinessDay() {
        val state = MarketTradingCalendar.state(
            ZonedDateTime.of(2026, 7, 2, 10, 0, 0, 0, zone)
        )

        assertEquals(TradingSessionState.Trading, state)
    }

    @Test
    fun stateReturnsHolidayForWeekendAndKnownHoliday() {
        assertEquals(
            TradingSessionState.Holiday,
            MarketTradingCalendar.state(ZonedDateTime.of(2026, 7, 4, 10, 0, 0, 0, zone)),
        )
        assertEquals(
            TradingSessionState.Holiday,
            MarketTradingCalendar.state(ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, zone)),
        )
    }

    @Test
    fun stateReturnsOffHoursOutsideTradingWindow() {
        val state = MarketTradingCalendar.state(
            ZonedDateTime.of(2026, 7, 2, 16, 0, 0, 0, zone)
        )

        assertEquals(TradingSessionState.OffHours, state)
    }
}
