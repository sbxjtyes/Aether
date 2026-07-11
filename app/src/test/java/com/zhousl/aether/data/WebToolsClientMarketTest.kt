package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsClientMarketTest {
    @Test
    fun fetchMarketIndicesParsesEastmoneyBatchQuoteResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "rc": 0,
                  "data": {
                    "diff": [
                      {"f2": 335025, "f3": 123, "f4": 409, "f5": 1000, "f6": 2000, "f12": "000001", "f13": 1, "f14": "上证指数"}
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val client = WebToolsClient(stockQuoteBaseUrl = server.url("/").toString())
            val indices = client.fetchMarketIndices(listOf("1.000001")).getOrThrow()

            assertEquals(1, indices.size)
            assertEquals("上证指数", indices.first().name)
            assertEquals(3350.25, indices.first().price, 0.001)
            assertEquals(1.23, indices.first().changePercent, 0.001)
            assertEquals("/api/qt/ulist.np/get", server.takeRequest().requestUrl?.encodedPath)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchSectorRankSupportsSortFieldsAndAscendingOrder() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "rc": 0,
                  "data": {
                    "diff": [
                      {"f12": "BK001", "f14": "测试板块", "f3": -321, "f8": 456, "f128": "龙头", "f136": 222, "f62": 123456}
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val client = WebToolsClient(stockSectorBaseUrl = server.url("/").toString())
            val sectors = client.fetchSectorRank(
                type = SectorType.Concept,
                limit = 12,
                sort = SectorSort.TurnoverRate,
                ascending = true,
            ).getOrThrow()

            assertEquals("测试板块", sectors.first().name)
            assertEquals(-3.21, sectors.first().changePercent, 0.001)
            assertEquals(4.56, sectors.first().turnoverRate, 0.001)
            val request = server.takeRequest().requestUrl
            assertEquals("f8", request?.queryParameter("fid"))
            assertEquals("0", request?.queryParameter("po"))
            assertEquals("m:90+t:3", request?.queryParameter("fs"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchMarketBreadthCountsPagesAndLimitPools() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "rc": 0,
                  "data": {
                    "total": 3,
                    "diff": [
                      {"f3": 120},
                      {"f3": -50},
                      {"f3": 0}
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(jsonResponse("""{"data":{"tc":2,"pool":[{}]}}"""))
        server.enqueue(jsonResponse("""{"data":{"tc":1,"pool":[{}]}}"""))
        server.start()
        try {
            val client = WebToolsClient(
                stockSectorBaseUrl = server.url("/").toString(),
                eastmoneyLimitPoolBaseUrl = server.url("/").toString(),
            )
            val breadth = client.fetchMarketBreadth(pageSize = 50, maxPages = 1).getOrThrow()

            assertEquals(1, breadth.risingCount)
            assertEquals(1, breadth.fallingCount)
            assertEquals(1, breadth.flatCount)
            assertEquals(2, breadth.limitUpCount)
            assertEquals(1, breadth.limitDownCount)
            assertFalse(breadth.isEstimatedLimitStats)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchWatchlistQuotesBatchesAndParsesTypedQuotes() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            jsonResponse(
                """
                {
                  "rc": 0,
                  "data": {
                    "diff": [
                      {"f2": 1700, "f3": 118, "f4": 20, "f5": 100, "f6": 1234, "f12": "600519", "f13": 1, "f14": "贵州茅台", "f15": 1800, "f16": 1600, "f17": 1680, "f18": 1680}
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        server.start()
        try {
            val client = WebToolsClient(stockQuoteBaseUrl = server.url("/").toString())
            val quotes = client.fetchWatchlistQuotes(listOf("600519.SH")).getOrThrow()

            assertEquals(1, quotes.size)
            assertEquals("600519.SH", quotes.first().symbol)
            assertEquals("贵州茅台", quotes.first().name)
            assertEquals(17.0, quotes.first().price, 0.001)
            assertEquals(1.18, quotes.first().changePercent, 0.001)
            assertTrue(server.takeRequest().requestUrl?.queryParameter("secids")?.contains("1.600519") == true)
        } finally {
            server.shutdown()
        }
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(body)
}
