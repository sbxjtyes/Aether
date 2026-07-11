package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsClientTest {
    @Test
    fun fetchUrlAsMarkdownConvertsHtmlIntoMarkdown() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html>
                      <head>
                        <title>Example Page</title>
                      </head>
                      <body>
                        <header>Top nav</header>
                        <main>
                          <article>
                            <h1>Hello from Aether</h1>
                            <p>Read the <a href="/docs">relative link</a>.</p>
                          </article>
                        </main>
                      </body>
                    </html>
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient()
            val result = client.fetchUrlAsMarkdown(server.url("/page").toString()).getOrThrow()

            assertEquals("Example Page", result.title)
            assertEquals(server.url("/page").toString(), result.finalUrl)
            assertTrue(result.markdown.contains("Example Page"))
            assertTrue(result.markdown.contains("Hello from Aether"))
            assertTrue(result.markdown.contains("relative link"))
            assertTrue(result.markdown.contains(server.url("/docs").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tavilySearchUsesBearerAuthAndSearchEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "query": "android agent",
                      "answer": "summary",
                      "results": [
                        {
                          "title": "Result One",
                          "url": "https://example.com/result",
                          "content": "Snippet"
                        }
                      ],
                      "response_time": "0.42",
                      "usage": { "credits": 1 },
                      "request_id": "req-123"
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient(tavilyBaseUrl = server.url("/").toString())
            val response = client.searchTavily(
                apiKey = "tvly-test",
                request = TavilySearchRequest(
                    query = "android agent",
                    searchDepth = "advanced",
                    includeRawContent = true,
                    includeDomains = listOf("example.com"),
                ),
            ).getOrThrow()

            assertEquals("summary", response.getString("answer"))

            val request = server.takeRequest()
            assertEquals("/search", request.path)
            assertEquals("Bearer tvly-test", request.getHeader("Authorization"))

            val payload = JSONObject(request.body.readUtf8())
            assertEquals("android agent", payload.getString("query"))
            assertEquals("advanced", payload.getString("search_depth"))
            assertEquals("markdown", payload.getString("include_raw_content"))
            assertEquals("basic", payload.getString("include_answer"))
            assertEquals(true, payload.getBoolean("include_favicon"))
            assertEquals("example.com", payload.getJSONArray("include_domains").getString(0))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stockSearchUsesEastmoneySuggestEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "QuotationCodeTable": {
                        "Data": [
                          {
                            "Code": "001896",
                            "Name": "豫能控股",
                            "QuoteID": "0.001896"
                          }
                        ],
                        "Status": 0
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient(stockBaseUrl = server.url("/").toString())
            val response = client.searchStocks(
                StockSearchRequest(
                    query = "豫能控股",
                    maxResults = 3,
                ),
            ).getOrThrow()

            assertEquals(
                "001896",
                response.getJSONObject("QuotationCodeTable").getJSONArray("Data").getJSONObject(0).getString("Code"),
            )

            val request = server.takeRequest()
            assertEquals("/api/suggest/get", request.requestUrl?.encodedPath)
            assertEquals("豫能控股", request.requestUrl?.queryParameter("input"))
            assertEquals("14", request.requestUrl?.queryParameter("type"))
            assertEquals("3", request.requestUrl?.queryParameter("count"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stockChartUsesEastmoneyQuoteAndKlineEndpoints() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "rc": 0,
                      "data": {
                        "f43": 1776,
                        "f44": 1867,
                        "f45": 1680,
                        "f46": 1698,
                        "f47": 2419721,
                        "f48": 4328468428.5,
                        "f57": "001896",
                        "f58": "豫能控股",
                        "f60": 1698,
                        "f86": 1778657670,
                        "f107": 0
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "rc": 0,
                      "data": {
                        "code": "001896",
                        "market": 0,
                        "name": "豫能控股",
                        "klines": [
                          "2026-05-13,16.98,17.76,18.67,16.80,2419721,4328468428.50,11.01,4.59,0.78,15.86"
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient(stockBaseUrl = server.url("/").toString())
            val response = client.fetchStockChart(
                StockChartRequest(
                    symbol = "001896.SZ",
                    range = "1mo",
                    interval = "1d",
                ),
            ).getOrThrow()

            assertEquals("0.001896", response.getString("secid"))
            assertEquals("001896", response.getJSONObject("quote").getJSONObject("data").getString("f57"))
            assertEquals(31, response.getInt("kline_limit"))

            val quoteRequest = server.takeRequest()
            assertEquals("/api/qt/stock/get", quoteRequest.requestUrl?.encodedPath)
            assertEquals("0.001896", quoteRequest.requestUrl?.queryParameter("secid"))

            val klineRequest = server.takeRequest()
            assertEquals("/api/qt/stock/kline/get", klineRequest.requestUrl?.encodedPath)
            assertEquals("0.001896", klineRequest.requestUrl?.queryParameter("secid"))
            assertEquals("101", klineRequest.requestUrl?.queryParameter("klt"))
            assertEquals("31", klineRequest.requestUrl?.queryParameter("lmt"))
            assertEquals(response.getString("kline_begin_date"), klineRequest.requestUrl?.queryParameter("beg"))
            assertEquals(response.getString("kline_end_date"), klineRequest.requestUrl?.queryParameter("end"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stockQuoteUsesEnhancedSingleQuoteFields() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "rc": 0,
                      "data": {
                        "f43": 119496,
                        "f57": "600519",
                        "f58": "贵州茅台",
                        "f86": 1782720719,
                        "f107": 1,
                        "f152": 2,
                        "f162": 1371,
                        "f164": 1806,
                        "f167": 633,
                        "f168": 53,
                        "f170": 225,
                        "f171": 548
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient(stockBaseUrl = server.url("/").toString())
            val response = client.fetchStockQuote(
                StockQuoteRequest(symbol = "600519.SH"),
            ).getOrThrow()

            assertEquals("1.600519", response.getString("secid"))
            assertEquals("600519", response.getJSONObject("quote").getJSONObject("data").getString("f57"))

            val request = server.takeRequest()
            assertEquals("/api/qt/stock/get", request.requestUrl?.encodedPath)
            assertEquals("1.600519", request.requestUrl?.queryParameter("secid"))
            val fields = request.requestUrl?.queryParameter("fields").orEmpty()
            assertTrue(fields.contains("f162"))
            assertTrue(fields.contains("f164"))
            assertTrue(fields.contains("f167"))
            assertTrue(fields.contains("f168"))
            assertTrue(fields.contains("f170"))
            assertTrue(fields.contains("f171"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stockQuotesUseEastmoneyBatchQuoteEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "rc": 0,
                      "data": {
                        "diff": [
                          {
                            "f2": 1667,
                            "f3": -83,
                            "f4": -14,
                            "f5": 1189567,
                            "f6": 1964757060.24,
                            "f7": 625,
                            "f8": 780,
                            "f9": 21717,
                            "f10": 73,
                            "f12": "001896",
                            "f13": 0,
                            "f14": "Example SZ",
                            "f15": 1712,
                            "f16": 1607,
                            "f17": 1697,
                            "f18": 1681,
                            "f20": 25434774771,
                            "f21": 25434774771,
                            "f23": 714,
                            "f24": 1195,
                            "f25": 22058,
                            "f62": 85497343.0,
                            "f115": 6776,
                            "f152": 2
                          },
                          {
                            "f2": 280450,
                            "f3": -117,
                            "f4": -3330,
                            "f5": 25132058,
                            "f6": 7108499200.0,
                            "f7": 287,
                            "f8": 17,
                            "f9": 3360,
                            "f10": 55,
                            "f12": "AAPL",
                            "f13": 105,
                            "f14": "Apple",
                            "f15": 288370,
                            "f16": 280220,
                            "f17": 286860,
                            "f18": 283780,
                            "f20": 4119068990200,
                            "f21": 4119068990200,
                            "f23": 3868,
                            "f24": 983,
                            "f25": 336,
                            "f62": 89689527.0,
                            "f115": 3360,
                            "f152": 2
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val client = WebToolsClient(stockBaseUrl = server.url("/").toString())
            val response = client.fetchStockQuotes(
                StockQuotesRequest(
                    symbols = listOf("001896.SZ", "600519.SH", "00700.HK", "AAPL", "105.MSFT", "AAPL"),
                ),
            ).getOrThrow()

            val resolved = response.getJSONArray("resolved_symbols")
            assertEquals(5, resolved.length())
            assertEquals("0.001896", resolved.getJSONObject(0).getString("secid"))
            assertEquals("1.600519", resolved.getJSONObject(1).getString("secid"))
            assertEquals("116.00700", resolved.getJSONObject(2).getString("secid"))
            assertEquals("105.AAPL", resolved.getJSONObject(3).getString("secid"))
            assertEquals("105.MSFT", resolved.getJSONObject(4).getString("secid"))

            val diff = response.getJSONObject("quotes").getJSONObject("data").getJSONArray("diff")
            assertEquals("001896", diff.getJSONObject(0).getString("f12"))
            assertEquals("AAPL", diff.getJSONObject(1).getString("f12"))

            val request = server.takeRequest()
            assertEquals("/api/qt/ulist.np/get", request.requestUrl?.encodedPath)
            assertEquals(
                "0.001896,1.600519,116.00700,105.AAPL,105.MSFT",
                request.requestUrl?.queryParameter("secids"),
            )
            val fields = request.requestUrl?.queryParameter("fields").orEmpty()
            assertTrue(fields.contains("f2"))
            assertTrue(fields.contains("f3"))
            assertTrue(fields.contains("f8"))
            assertTrue(fields.contains("f9"))
            assertTrue(fields.contains("f23"))
            assertTrue(fields.contains("f24"))
            assertTrue(fields.contains("f25"))
            assertTrue(fields.contains("f115"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun stockQuotesReturnPerSymbolResolutionErrors() = runBlocking {
        val client = WebToolsClient(stockBaseUrl = "https://example.invalid/")
        val response = client.fetchStockQuotes(
            StockQuotesRequest(symbols = listOf("not a symbol")),
        ).getOrThrow()

        assertEquals(0, response.getJSONArray("resolved_symbols").length())
        assertEquals(1, response.getJSONArray("errors").length())
        assertEquals(0, response.getJSONObject("quotes").getJSONObject("data").getJSONArray("diff").length())
    }
}
