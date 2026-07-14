package com.zhousl.aether.data

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val DefaultFetchMarkdownChars = 20_000
private const val MinFetchMarkdownChars = 500
private const val MaxFetchMarkdownChars = 100_000
private const val MaxWebResponseBytes = 2 * 1024 * 1024
private const val MaxJsonResponseBytes = 4 * 1024 * 1024
private const val DefaultUserAgent =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
private const val DefaultTavilyBaseUrl = "https://api.tavily.com/"
private const val DefaultStockSearchBaseUrl = "https://searchapi.eastmoney.com/"
private const val DefaultStockQuoteBaseUrl = "https://push2delay.eastmoney.com/"
private const val DefaultStockKlineBaseUrl = "https://push2his.eastmoney.com/"
private const val DefaultStockSectorBaseUrl = "https://push2.eastmoney.com/"
private const val DefaultSinaQuoteBaseUrl = "https://hq.sinajs.cn/"
private const val DefaultEastmoneyLimitPoolBaseUrl = "https://push2ex.eastmoney.com/"
private const val EastmoneySuggestToken = "D43BF722C8E33BDC906FB84D85E326E8"
private const val EastmoneyUtToken = "bd1d9ddb04089700cf9c27f6f7426281"
private const val EastmoneySectorFields = "f12,f14,f3,f128,f136,f8,f62,f184"
private const val EastmoneyBreadthFields = "f2,f3,f12,f14,f15,f16,f17,f18"
private const val EastmoneySingleQuoteFields =
    "f43,f44,f45,f46,f47,f48,f49,f50,f51,f52,f57,f58,f60,f71,f84,f85,f86,f107,f116,f117,f152,f162,f164,f167,f168,f169,f170,f171"
private const val EastmoneyBatchQuoteFields =
    "f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f12,f13,f14,f15,f16,f17,f18,f20,f21,f23,f24,f25,f62,f115,f152"

data class FetchedWebPage(
    val requestUrl: String,
    val finalUrl: String,
    val title: String,
    val contentType: String,
    val markdown: String,
    val wasTruncated: Boolean,
)

data class TavilySearchRequest(
    val query: String,
    val topic: String = "general",
    val searchDepth: String = "basic",
    val maxResults: Int = 5,
    val timeRange: String? = null,
    val includeAnswer: Boolean = true,
    val includeRawContent: Boolean = false,
    val includeFavicon: Boolean = true,
    val includeDomains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    val country: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)

data class StockSearchRequest(
    val query: String,
    val maxResults: Int = 10,
)

data class StockQuoteRequest(
    val symbol: String,
)

data class StockChartRequest(
    val symbol: String,
    val range: String = "1d",
    val interval: String = "1m",
    val includePrePost: Boolean = false,
)

data class StockQuotesRequest(
    val symbols: List<String>,
)

class WebToolsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor(MetadataProtectionInterceptor)
        .build(),
    private val tavilyBaseUrl: String = DefaultTavilyBaseUrl,
    private val stockBaseUrl: String = "",
    private val stockSearchBaseUrl: String = DefaultStockSearchBaseUrl,
    private val stockQuoteBaseUrl: String = DefaultStockQuoteBaseUrl,
    private val stockKlineBaseUrl: String = DefaultStockKlineBaseUrl,
    private val stockSectorBaseUrl: String = DefaultStockSectorBaseUrl,
    private val eastmoneyLimitPoolBaseUrl: String = DefaultEastmoneyLimitPoolBaseUrl,
) {
    private val htmlToMarkdownConverter = FlexmarkHtmlConverter.builder().build()

    suspend fun fetchUrlAsMarkdown(
        url: String,
        maxChars: Int = DefaultFetchMarkdownChars,
    ): Result<FetchedWebPage> = runCatching {
        withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeUrl(url)
            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", DefaultUserAgent)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,text/markdown,text/plain,application/xml;q=0.9,*/*;q=0.8",
                )
                .build()

            httpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                val finalUrl = response.request.url.toString()
                val bodyString = response.readBodyStringLimited(MaxWebResponseBytes)
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} while fetching $normalizedUrl.")
                }

                val markdown = convertResponseToMarkdown(
                    bodyString = bodyString,
                    contentType = contentType,
                    finalUrl = finalUrl,
                )
                val normalizedMarkdown = normalizeMarkdown(markdown)
                val boundedMaxChars = maxChars.coerceIn(MinFetchMarkdownChars, MaxFetchMarkdownChars)
                val truncatedMarkdown = normalizedMarkdown.truncateAtWordBoundary(boundedMaxChars)

                FetchedWebPage(
                    requestUrl = normalizedUrl,
                    finalUrl = finalUrl,
                    title = extractTitle(bodyString, contentType),
                    contentType = contentType,
                    markdown = truncatedMarkdown,
                    wasTruncated = truncatedMarkdown.length < normalizedMarkdown.length,
                )
            }
        }
    }

    suspend fun searchTavily(
        apiKey: String,
        request: TavilySearchRequest,
    ): Result<JSONObject> = runCatching {
        withContext(Dispatchers.IO) {
            val trimmedApiKey = apiKey.trim()
            if (trimmedApiKey.isBlank()) {
                error("Tavily API key is not configured.")
            }

            val payload = JSONObject().apply {
                put("query", request.query.trim())
                put("topic", request.topic)
                put("search_depth", request.searchDepth)
                put("max_results", request.maxResults.coerceIn(1, 20))
                put("include_answer", if (request.includeAnswer) "basic" else false)
                put("include_raw_content", if (request.includeRawContent) "markdown" else false)
                put("include_favicon", request.includeFavicon)
                put("include_usage", true)
                request.timeRange?.takeIf { it.isNotBlank() }?.let { put("time_range", it) }
                request.country?.takeIf { it.isNotBlank() }?.let { put("country", it) }
                request.startDate?.takeIf { it.isNotBlank() }?.let { put("start_date", it) }
                request.endDate?.takeIf { it.isNotBlank() }?.let { put("end_date", it) }
                if (request.includeDomains.isNotEmpty()) {
                    put(
                        "include_domains",
                        JSONArray().apply { request.includeDomains.forEach(::put) },
                    )
                }
                if (request.excludeDomains.isNotEmpty()) {
                    put(
                        "exclude_domains",
                        JSONArray().apply { request.excludeDomains.forEach(::put) },
                    )
                }
            }

            val searchEndpoint = buildEndpoint("search")
            val httpRequest = Request.Builder()
                .url(searchEndpoint)
                .header("Authorization", "Bearer $trimmedApiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JsonMediaType))
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val bodyString = response.readBodyStringLimited(MaxJsonResponseBytes)
                val json = bodyString.toJsonObjectOrNull()
                if (!response.isSuccessful) {
                    val detail = json?.optString("detail").orEmpty()
                        .ifBlank { json?.optString("message").orEmpty() }
                        .ifBlank { "HTTP ${response.code} from Tavily." }
                    error(detail)
                }
                json ?: error("Tavily returned non-JSON content.")
            }
        }
    }

    suspend fun searchStocks(
        request: StockSearchRequest,
    ): Result<JSONObject> = runCatching {
        withContext(Dispatchers.IO) {
            val query = request.query.trim()
            if (query.isBlank()) {
                error("Stock search query is required.")
            }

            val endpoint = buildStockEndpoint(resolveStockBaseUrl(stockSearchBaseUrl), "api", "suggest", "get") {
                addQueryParameter("input", query)
                addQueryParameter("type", "14")
                addQueryParameter("token", EastmoneySuggestToken)
                addQueryParameter("count", request.maxResults.coerceIn(1, 25).toString())
            }
            executeJsonGet(endpoint, "stock search")
        }
    }

    suspend fun fetchStockChart(
        request: StockChartRequest,
    ): Result<JSONObject> = runCatching {
        withContext(Dispatchers.IO) {
            val symbol = request.symbol.trim()
            if (symbol.isBlank()) {
                error("Stock symbol is required.")
            }

            // 支持中文名/模糊输入：无法从代码推断市场时，自动走搜索取 QuoteID。
            val resolvedSymbol = resolveEastmoneySecidFlexible(symbol)
            val quoteEndpoint = buildStockEndpoint(resolveStockBaseUrl(stockQuoteBaseUrl), "api", "qt", "stock", "get") {
                addQueryParameter("secid", resolvedSymbol.secid)
                addQueryParameter("fields", EastmoneySingleQuoteFields)
            }
            val quoteJson = executeJsonGet(quoteEndpoint, "stock quote")
            if (quoteJson.optInt("rc", -1) != 0 || quoteJson.optJSONObject("data") == null) {
                error("Stock quote response did not include usable data for ${resolvedSymbol.secid}.")
            }

            val klineConfig = buildEastmoneyKlineConfig(request.range, request.interval)
            val klineEndpoint = buildStockEndpoint(resolveStockBaseUrl(stockKlineBaseUrl), "api", "qt", "stock", "kline", "get") {
                addQueryParameter("secid", resolvedSymbol.secid)
                addQueryParameter("fields1", "f1,f2,f3,f4,f5,f6")
                addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61")
                addQueryParameter("klt", klineConfig.klt)
                addQueryParameter("fqt", "1")
                addQueryParameter("beg", klineConfig.beginDate)
                addQueryParameter("end", klineConfig.endDate)
                addQueryParameter("lmt", klineConfig.limit.toString())
            }
            val klineResult = runCatching { executeJsonGet(klineEndpoint, "stock kline") }

            JSONObject().apply {
                put("source", "Eastmoney")
                put("resolved_symbol", resolvedSymbol.symbol)
                put("secid", resolvedSymbol.secid)
                put("market", resolvedSymbol.market)
                put("resolved_from_query", resolvedSymbol.resolvedFromQuery)
                put("range", request.range)
                put("interval", request.interval)
                put("klt", klineConfig.klt)
                put("kline_limit", klineConfig.limit)
                put("kline_begin_date", klineConfig.beginDate)
                put("kline_end_date", klineConfig.endDate)
                put("quote", quoteJson)
                klineResult
                    .onSuccess { put("kline", it) }
                    .onFailure { throwable ->
                        put("kline_error", throwable.message ?: "Stock kline request failed.")
                    }
            }
        }
    }

    suspend fun fetchStockQuote(
        request: StockQuoteRequest,
    ): Result<JSONObject> = runCatching {
        withContext(Dispatchers.IO) {
            val symbol = request.symbol.trim()
            if (symbol.isBlank()) {
                error("Stock symbol is required.")
            }

            // 支持中文名/模糊输入：无法从代码推断市场时，自动走搜索取 QuoteID。
            val resolvedSymbol = resolveEastmoneySecidFlexible(symbol)
            val quoteEndpoint = buildStockEndpoint(resolveStockBaseUrl(stockQuoteBaseUrl), "api", "qt", "stock", "get") {
                addQueryParameter("secid", resolvedSymbol.secid)
                addQueryParameter("fields", EastmoneySingleQuoteFields)
            }
            val quoteJson = executeJsonGet(quoteEndpoint, "stock quote")
            if (quoteJson.optInt("rc", -1) != 0 || quoteJson.optJSONObject("data") == null) {
                error("Stock quote response did not include usable data for ${resolvedSymbol.secid}.")
            }

            JSONObject().apply {
                put("source", "Eastmoney")
                put("resolved_symbol", resolvedSymbol.symbol)
                put("secid", resolvedSymbol.secid)
                put("market", resolvedSymbol.market)
                put("resolved_from_query", resolvedSymbol.resolvedFromQuery)
                put("quote", quoteJson)
            }
        }
    }

    /**
     * 获取主要市场指数实时行情。
     * 复用东方财富批量行情接口（ulist.np/get），传入五大指数 secid。
     */
    suspend fun fetchMarketIndices(
        secids: List<String> = MainMarketIndexSecids,
    ): Result<List<MarketIndex>> = runCatching {
        withContext(Dispatchers.IO) {
            // 不传 fltt 参数，沿用东财默认整数编码（值需 ÷100），与现有 normalizeEastmoneyQuote 保持一致。
            val endpoint = buildStockEndpoint(
                resolveStockBaseUrl(stockQuoteBaseUrl),
                "api", "qt", "ulist.np", "get",
            ) {
                addQueryParameter("secids", secids.joinToString(","))
                addQueryParameter("fields", EastmoneyBatchQuoteFields)
                addQueryParameter("invt", "2")
            }
            val json = executeJsonGet(endpoint, "market indices")
            val diff = json.optJSONObject("data")?.optJSONArray("diff") ?: JSONArray()
            (0 until diff.length()).mapNotNull { i ->
                val item = diff.optJSONObject(i) ?: return@mapNotNull null
                val market = item.optString("f13")
                val code = item.optString("f12")
                // 东财整数编码：价格 / 100 = 实际价格（例如 335025 → 3350.25）
                val rawPrice = item.optDouble("f2", Double.NaN)
                val rawChange = item.optDouble("f4", Double.NaN)
                val rawChangePct = item.optDouble("f3", Double.NaN)
                if (rawPrice.isNaN() || rawPrice <= 0) return@mapNotNull null
                MarketIndex(
                    code = code,
                    name = item.optString("f14"),
                    secid = "$market.$code",
                    price = rawPrice / 100.0,
                    change = if (rawChange.isNaN()) 0.0 else rawChange / 100.0,
                    changePercent = if (rawChangePct.isNaN()) 0.0 else rawChangePct / 100.0,
                    volume = item.optLong("f5", 0L),
                    amount = item.optDouble("f6", 0.0),
                )
            }
        }
    }

    suspend fun fetchSectorRank(
        type: SectorType = SectorType.Industry,
        limit: Int = 50,
        sort: SectorSort = SectorSort.ChangePercent,
        ascending: Boolean = false,
    ): Result<List<SectorItem>> = runCatching {
        withContext(Dispatchers.IO) {
            val fsParam = when (type) {
                SectorType.Industry -> "m:90+t:2"
                SectorType.Concept -> "m:90+t:3"
            }
            val fid = when (sort) {
                SectorSort.ChangePercent -> "f3"
                SectorSort.TurnoverRate -> "f8"
            }
            val endpoint = buildStockEndpoint(
                stockSectorBaseUrl,
                "api", "qt", "clist", "get",
            ) {
                addQueryParameter("pn", "1")
                addQueryParameter("pz", limit.coerceIn(1, 100).toString())
                addQueryParameter("po", if (ascending) "0" else "1")
                addQueryParameter("np", "1")
                addQueryParameter("ut", EastmoneyUtToken)
                addQueryParameter("invt", "2")
                addQueryParameter("fid", fid)
                addQueryParameter("fs", fsParam)
                addQueryParameter("fields", EastmoneySectorFields)
            }
            val json = executeJsonGet(endpoint, "sector rank")
            val diff = json.optJSONObject("data")?.optJSONArray("diff") ?: JSONArray()
            (0 until diff.length()).mapNotNull { i ->
                val item = diff.optJSONObject(i) ?: return@mapNotNull null
                val rawChangePct = item.optDouble("f3", Double.NaN)
                SectorItem(
                    code = item.optString("f12"),
                    name = item.optString("f14"),
                    changePercent = if (rawChangePct.isNaN()) 0.0 else rawChangePct / 100.0,
                    leadingStock = item.optString("f128"),
                    leadingStockChange = item.optDouble("f136", 0.0) / 100.0,
                    turnoverRate = item.optDouble("f8", 0.0) / 100.0,
                    amount = item.optDouble("f62", 0.0),
                )
            }
        }
    }

    suspend fun fetchMarketBreadth(
        pageSize: Int = 200,
        maxPages: Int = 30,
    ): Result<MarketBreadth> = runCatching {
        withContext(Dispatchers.IO) {
            var rising = 0
            var falling = 0
            var flat = 0
            var estimatedLimitUp = 0
            var estimatedLimitDown = 0
            var page = 1
            var total = Int.MAX_VALUE
            val pz = pageSize.coerceIn(50, 500)

            while ((page - 1) * pz < total && page <= maxPages.coerceAtLeast(1)) {
                val endpoint = buildStockEndpoint(
                    stockSectorBaseUrl,
                    "api", "qt", "clist", "get",
                ) {
                    addQueryParameter("pn", page.toString())
                    addQueryParameter("pz", pz.toString())
                    addQueryParameter("po", "1")
                    addQueryParameter("np", "1")
                    addQueryParameter("ut", EastmoneyUtToken)
                    addQueryParameter("invt", "2")
                    addQueryParameter("fid", "f3")
                    addQueryParameter("fs", "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23")
                    addQueryParameter("fields", EastmoneyBreadthFields)
                }
                val json = executeJsonGet(endpoint, "market breadth")
                val data = json.optJSONObject("data") ?: break
                total = data.optInt("total", total)
                val diff = data.optJSONArray("diff") ?: JSONArray()
                if (diff.length() == 0) break
                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    val pct = item.optDouble("f3", Double.NaN).takeUnless { it.isNaN() }?.div(100.0) ?: continue
                    when {
                        pct > 0.0 -> rising++
                        pct < 0.0 -> falling++
                        else -> flat++
                    }
                    if (pct >= 9.8) estimatedLimitUp++
                    if (pct <= -9.8) estimatedLimitDown++
                }
                page++
            }

            val poolCounts = fetchLimitPoolCounts().getOrNull()
            MarketBreadth(
                risingCount = rising,
                fallingCount = falling,
                flatCount = flat,
                limitUpCount = poolCounts?.first ?: estimatedLimitUp,
                limitDownCount = poolCounts?.second ?: estimatedLimitDown,
                isEstimatedLimitStats = poolCounts == null,
            )
        }
    }

    suspend fun fetchWatchlistQuotes(
        symbols: List<String>,
    ): Result<List<WatchlistQuote>> = runCatching {
        withContext(Dispatchers.IO) {
            val normalizedSymbols = symbols
                .map { it.trim().uppercase(Locale.US) }
                .filter(String::isNotBlank)
                .distinct()
            if (normalizedSymbols.isEmpty()) return@withContext emptyList()

            val quotes = mutableListOf<WatchlistQuote>()
            normalizedSymbols.chunked(20).forEach { chunk ->
                val resolved = chunk.mapNotNull { symbol ->
                    runCatching { symbol to resolveEastmoneySecid(symbol) }.getOrNull()
                }
                if (resolved.isEmpty()) return@forEach
                val secids = resolved.map { it.second.secid }
                val symbolBySecid = resolved.associate { (input, secid) -> secid.secid to input }
                val endpoint = buildStockEndpoint(resolveStockBaseUrl(stockQuoteBaseUrl), "api", "qt", "ulist.np", "get") {
                    addQueryParameter("secids", secids.joinToString(","))
                    addQueryParameter("fields", EastmoneyBatchQuoteFields)
                    addQueryParameter("invt", "2")
                }
                val json = executeJsonGet(endpoint, "watchlist quotes")
                val diff = json.optJSONObject("data")?.optJSONArray("diff") ?: JSONArray()
                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    parseWatchlistQuote(item, symbolBySecid)?.let(quotes::add)
                }
            }
            quotes
        }
    }

    private fun fetchLimitPoolCounts(): Result<Pair<Int, Int>> = runCatching {
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val up = fetchLimitPoolCount("getTopicZTPool", date)
        val down = fetchLimitPoolCount("getTopicDTPool", date)
        up to down
    }

    private fun fetchLimitPoolCount(
        pathSegment: String,
        date: String,
    ): Int {
        val endpoint = buildStockEndpoint(eastmoneyLimitPoolBaseUrl, pathSegment) {
            addQueryParameter("ut", EastmoneyUtToken)
            addQueryParameter("d", date)
            addQueryParameter("pageindex", "0")
            addQueryParameter("pagesize", "1")
            addQueryParameter("sort", "fbt:asc")
        }
        val json = executeJsonGet(endpoint, "limit pool")
        val data = json.optJSONObject("data") ?: return 0
        val pool = data.optJSONArray("pool")
        if (pool != null) return data.optInt("tc", pool.length())
        val diff = data.optJSONArray("diff")
        if (diff != null) return data.optInt("total", diff.length())
        return data.optInt("total", data.optInt("count", 0))
    }

    private fun parseWatchlistQuote(
        item: JSONObject,
        symbolBySecid: Map<String, String>,
    ): WatchlistQuote? {
        val market = item.optString("f13")
        val code = item.optString("f12")
        val secid = "$market.$code"
        val price = item.optEastmoneyPrice("f2", market) ?: return null
        val preClose = item.optEastmoneyPrice("f18", market) ?: 0.0
        val change = item.optEastmoneyPriceDelta("f4", market) ?: (price - preClose)
        val changePercent = item.optEastmoneyPercent("f3") ?: if (preClose > 0.0) {
            change / preClose * 100.0
        } else {
            0.0
        }
        return WatchlistQuote(
            symbol = symbolBySecid[secid] ?: code,
            name = item.optString("f14"),
            price = price,
            change = change,
            changePercent = changePercent,
            volume = item.optLong("f5", 0L),
            amount = item.optDouble("f6", 0.0),
            high = item.optEastmoneyPrice("f15", market) ?: 0.0,
            low = item.optEastmoneyPrice("f16", market) ?: 0.0,
            open = item.optEastmoneyPrice("f17", market) ?: 0.0,
            preClose = preClose,
        )
    }

    private fun JSONObject.optNumberAsDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun JSONObject.optEastmoneyPrice(
        key: String,
        market: String,
    ): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        if (rawValue <= 0.0) return null
        val divisor = if (market == "105" && rawValue >= 10_000.0) 1_000.0 else 100.0
        return rawValue / divisor
    }

    private fun JSONObject.optEastmoneyPriceDelta(
        key: String,
        market: String,
    ): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        val divisor = if (market == "105" && kotlin.math.abs(rawValue) >= 10_000.0) 1_000.0 else 100.0
        return rawValue / divisor
    }

    private fun JSONObject.optEastmoneyPercent(key: String): Double? {
        val rawValue = optNumberAsDouble(key) ?: return null
        return rawValue / 100.0
    }

    /**
     * 使用新浪财经接口获取实时行情（延迟约 1–3 秒，比东方财富更快）。
     * 符号格式转换：600519.SH → sh600519，000001.SZ → sz000001
     */
    suspend fun fetchSinaQuotes(
        symbols: List<String>,
    ): Result<List<WatchlistQuote>> = runCatching {
        withContext(Dispatchers.IO) {
            if (symbols.isEmpty()) return@withContext emptyList()
            val sinaCodes = symbols.mapNotNull { sym -> convertToSinaCode(sym) }
            if (sinaCodes.isEmpty()) return@withContext emptyList()

            // 新浪接口不走用户自定义的东财 URL，固定使用新浪域名。
            val url = "${DefaultSinaQuoteBaseUrl}list=${sinaCodes.joinToString(",")}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DefaultUserAgent)
                .header("Referer", "https://finance.sina.com.cn")
                .header("Accept", "text/plain,*/*")
                .build()

            val body = httpClient.newCall(request).execute().use { resp ->
                resp.body?.string().orEmpty()
            }

            parseSinaQuoteResponse(body, symbols)
        }
    }

    private fun convertToSinaCode(symbol: String): String? {
        val upper = symbol.trim().uppercase(Locale.US)
        return when {
            upper.endsWith(".SH") || upper.endsWith(".SS") -> "sh${upper.substringBefore('.')}"
            upper.endsWith(".SZ") -> "sz${upper.substringBefore('.')}"
            upper.matches(Regex("^6\\d{5}$")) -> "sh$upper"
            upper.matches(Regex("^[038]\\d{5}$")) -> "sz$upper"
            else -> null
        }
    }

    private fun parseSinaQuoteResponse(body: String, originalSymbols: List<String>): List<WatchlistQuote> {
        // 预先建立 sina code → 原始符号 的反查表，避免用行号匹配（行号会因空行错位）。
        val sinaToOriginal = originalSymbols.associateBy { sym ->
            convertToSinaCode(sym)?.lowercase(Locale.US) ?: ""
        }.filterKeys { it.isNotBlank() }

        val results = mutableListOf<WatchlistQuote>()
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || !trimmed.startsWith("var hq_str_")) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val codeRaw = trimmed.substring("var hq_str_".length, eqIdx).trim().lowercase(Locale.US)
            val dataStr = trimmed.substring(eqIdx + 1).trim().trimStart('"').trimEnd(';', '"')
            if (dataStr.isBlank()) continue
            val parts = dataStr.split(",")
            if (parts.size < 10) continue
            val name = parts.getOrElse(0) { "" }
            val open = parts.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0
            val preClose = parts.getOrElse(2) { "0" }.toDoubleOrNull() ?: 0.0
            val price = parts.getOrElse(3) { "0" }.toDoubleOrNull() ?: 0.0
            val high = parts.getOrElse(4) { "0" }.toDoubleOrNull() ?: 0.0
            val low = parts.getOrElse(5) { "0" }.toDoubleOrNull() ?: 0.0
            val volume = parts.getOrElse(8) { "0" }.toLongOrNull() ?: 0L
            val amount = parts.getOrElse(9) { "0" }.toDoubleOrNull() ?: 0.0
            if (price <= 0 && name.isBlank()) continue
            val change = price - preClose
            val changePct = if (preClose > 0) (change / preClose * 100.0) else 0.0
            // 优先从反查表取原始符号；找不到时从 codeRaw 自行还原。
            val symbol = sinaToOriginal[codeRaw] ?: when {
                codeRaw.startsWith("sh") -> "${codeRaw.substring(2).uppercase(Locale.US)}.SH"
                codeRaw.startsWith("sz") -> "${codeRaw.substring(2).uppercase(Locale.US)}.SZ"
                else -> codeRaw.uppercase(Locale.US)
            }
            results.add(
                WatchlistQuote(
                    symbol = symbol,
                    name = name,
                    price = price,
                    change = change,
                    changePercent = changePct,
                    volume = volume,
                    amount = amount,
                    high = high,
                    low = low,
                    open = open,
                    preClose = preClose,
                )
            )
        }
        return results
    }

    suspend fun fetchStockQuotes(
        request: StockQuotesRequest,
    ): Result<JSONObject> = runCatching {
        withContext(Dispatchers.IO) {
            val symbols = request.symbols
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(20)
                .toList()
            if (symbols.isEmpty()) {
                error("At least one stock symbol is required.")
            }

            val resolvedSymbols = JSONArray()
            val errors = JSONArray()
            val secids = buildList {
                symbols.forEach { symbol ->
                    // 批量行情同样支持中文名：解析失败时自动搜索。
                    runCatching { resolveEastmoneySecidFlexible(symbol) }
                        .onSuccess { resolved ->
                            add(resolved.secid)
                            resolvedSymbols.put(
                                JSONObject().apply {
                                    put("input", symbol)
                                    put("symbol", resolved.symbol)
                                    put("market", resolved.market)
                                    put("secid", resolved.secid)
                                    put("resolved_from_query", resolved.resolvedFromQuery)
                                },
                            )
                        }
                        .onFailure { throwable ->
                            errors.put(
                                JSONObject().apply {
                                    put("symbol", symbol)
                                    put("errmsg", throwable.message ?: "Unable to resolve stock symbol.")
                                },
                            )
                        }
                }
            }
            if (secids.isEmpty()) {
                return@withContext JSONObject().apply {
                    put("source", "Eastmoney")
                    put("requested_symbols", JSONArray(symbols))
                    put("resolved_symbols", resolvedSymbols)
                    put(
                        "quotes",
                        JSONObject().apply {
                            put(
                                "data",
                                JSONObject().apply {
                                    put("diff", JSONArray())
                                },
                            )
                        },
                    )
                    put("errors", errors)
                }
            }

            val endpoint = buildStockEndpoint(resolveStockBaseUrl(stockQuoteBaseUrl), "api", "qt", "ulist.np", "get") {
                addQueryParameter("secids", secids.joinToString(","))
                addQueryParameter("fields", EastmoneyBatchQuoteFields)
            }
            val quoteJson = executeJsonGet(endpoint, "stock quotes")
            if (quoteJson.optInt("rc", -1) != 0 || quoteJson.optJSONObject("data") == null) {
                error("Stock quotes response did not include usable data.")
            }

            JSONObject().apply {
                put("source", "Eastmoney")
                put("requested_symbols", JSONArray(symbols))
                put("resolved_symbols", resolvedSymbols)
                put("quotes", quoteJson)
                if (errors.length() > 0) {
                    put("errors", errors)
                }
            }
        }
    }

    private fun resolveStockBaseUrl(defaultBaseUrl: String): String =
        stockBaseUrl.trim().ifBlank { defaultBaseUrl }

    /**
     * 先按代码规则解析 secid；若输入是中文名/无法推断市场的模糊文本，
     * 则自动调用东方财富搜索接口，取最匹配结果的 QuoteID。
     * 这样模型可以直接 quote("法拉电子")，不必先 search 再 quote。
     */
    private suspend fun resolveEastmoneySecidFlexible(symbol: String): EastmoneySecid {
        val trimmed = symbol.trim()
        if (trimmed.isBlank()) {
            error("Stock symbol is required.")
        }
        runCatching { return resolveEastmoneySecid(trimmed) }

        val searchJson = searchStocks(
            StockSearchRequest(query = trimmed, maxResults = 8),
        ).getOrElse { throwable ->
            error(
                "Unable to infer stock market for '$symbol', and search failed: " +
                    (throwable.message ?: "unknown error"),
            )
        }
        val matches = searchJson
            .optJSONObject("QuotationCodeTable")
            ?.optJSONArray("Data")
            ?: JSONArray()
        if (matches.length() == 0) {
            error("Unable to infer stock market for '$symbol'. No search matches found.")
        }

        val normalizedQuery = trimmed.lowercase(Locale.US)
        var chosen: JSONObject? = null
        for (index in 0 until matches.length()) {
            val item = matches.optJSONObject(index) ?: continue
            val name = item.optString("Name")
            val code = item.optString("Code")
            val pinyin = item.optString("PinYin")
            if (
                name.equals(trimmed, ignoreCase = true) ||
                code.equals(trimmed, ignoreCase = true) ||
                pinyin.equals(normalizedQuery, ignoreCase = true)
            ) {
                chosen = item
                break
            }
        }
        val best = chosen ?: matches.optJSONObject(0)
            ?: error("Unable to infer stock market for '$symbol'. No usable search match.")

        val quoteId = best.optString("QuoteID").ifBlank {
            val market = best.optString("MktNum")
            val code = best.optString("Code")
            if (market.isNotBlank() && code.isNotBlank()) "$market.$code" else ""
        }
        if (quoteId.isBlank()) {
            error("Unable to resolve QuoteID for '$symbol' from search results.")
        }

        return resolveEastmoneySecid(quoteId).copy(
            resolvedFromQuery = true,
            matchedName = best.optString("Name"),
        )
    }

    private fun resolveEastmoneySecid(symbol: String): EastmoneySecid {
        val normalized = symbol.trim().uppercase(Locale.US)
        val directSecid = Regex("^(0|1|100|105|116)\\.([A-Z0-9.-]+)$").matchEntire(normalized)
        if (directSecid != null) {
            val market = directSecid.groupValues[1]
            val code = directSecid.groupValues[2]
            return EastmoneySecid(
                symbol = code,
                market = market,
                secid = "$market.$code",
            )
        }

        val code = normalized.substringBefore('.')
        val suffix = normalized.substringAfter('.', "")

        val market = when {
            suffix in setOf("SZ", "XSHE") -> "0"
            suffix in setOf("SS", "SH", "XSHG") -> "1"
            suffix == "HK" -> "116"
            suffix == "US" -> "105"
            normalized.startsWith("^") -> "100"
            normalized.matches(Regex("^6\\d{5}$")) -> "1"
            normalized.matches(Regex("^[038]\\d{5}$")) -> "0"
            normalized.matches(Regex("^\\d{5}$")) -> "116"
            normalized.matches(Regex("^[A-Z][A-Z0-9.-]{0,9}$")) -> "105"
            else -> ""
        }

        if (market.isBlank()) {
            error("Unable to infer stock market for '$symbol'. Use search first, then pass the returned QuoteID/secid.")
        }

        return EastmoneySecid(
            symbol = code,
            market = market,
            secid = "$market.$code",
        )
    }

    private fun buildEastmoneyKlineConfig(
        range: String,
        interval: String,
    ): EastmoneyKlineConfig {
        val normalizedInterval = interval.trim().lowercase(Locale.US).ifBlank { "1d" }
        val klt = when (normalizedInterval) {
            "1m", "1min" -> "1"
            "5m", "5min" -> "5"
            "15m", "15min" -> "15"
            "30m", "30min" -> "30"
            "60m", "1h", "60min" -> "60"
            "1wk", "1w", "week" -> "102"
            "1mo", "1mon", "month" -> "103"
            else -> "101"
        }
        val normalizedRange = range.trim().lowercase(Locale.US).ifBlank { "1mo" }
        val today = LocalDate.now()
        val beginDate = when (normalizedRange) {
            "1d" -> today.minusDays(1)
            "5d" -> today.minusDays(10)
            "1mo" -> today.minusMonths(1)
            "3mo" -> today.minusMonths(3)
            "6mo" -> today.minusMonths(6)
            "1y" -> today.minusYears(1)
            "2y" -> today.minusYears(2)
            "5y" -> today.minusYears(5)
            "10y" -> today.minusYears(10)
            "max" -> LocalDate.of(1990, 1, 1)
            else -> today.minusMonths(6)
        }.format(DateTimeFormatter.BASIC_ISO_DATE)
        val limit = when {
            klt in setOf("1", "5", "15", "30", "60") -> when (normalizedRange) {
                "1d" -> 300
                "5d" -> 1_500
                "1mo" -> 2_000
                else -> 2_000
            }
            normalizedRange == "5d" -> 5
            normalizedRange == "1mo" -> 31
            normalizedRange == "3mo" -> 93
            normalizedRange == "6mo" -> 186
            normalizedRange == "1y" -> 366
            normalizedRange == "5y" -> 1_830
            normalizedRange == "max" -> 10_000
            else -> 200
        }
        return EastmoneyKlineConfig(
            klt = klt,
            limit = limit,
            beginDate = beginDate,
            endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE),
        )
    }

    private data class EastmoneySecid(
        val symbol: String,
        val market: String,
        val secid: String,
        /** 是否通过中文名/模糊搜索解析得到（而非直接从代码推断）。 */
        val resolvedFromQuery: Boolean = false,
        /** 搜索命中时的证券名称，便于上层回显。 */
        val matchedName: String = "",
    )

    private data class EastmoneyKlineConfig(
        val klt: String,
        val limit: Int,
        val beginDate: String,
        val endDate: String,
    )

    private fun buildEndpoint(pathSegment: String): String {
        val baseUrl = tavilyBaseUrl.trim().toHttpUrlOrNull()
            ?: error("Tavily base URL is invalid.")
        return baseUrl.newBuilder()
            .addPathSegments(pathSegment)
            .build()
            .toString()
    }

    private fun buildStockEndpoint(
        baseUrlValue: String,
        vararg pathSegments: String,
        configure: okhttp3.HttpUrl.Builder.() -> Unit,
    ): String {
        val baseUrl = baseUrlValue.trim().toHttpUrlOrNull()
            ?: error("Stock data base URL is invalid.")
        return baseUrl.newBuilder()
            .apply {
                pathSegments.forEach { addPathSegment(it) }
                configure()
            }
            .build()
            .toString()
    }

    private fun executeJsonGet(
        url: String,
        label: String,
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DefaultUserAgent)
            .header("Accept", "application/json,text/plain,*/*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyString = response.readBodyStringLimited(MaxJsonResponseBytes)
            val json = bodyString.toJsonObjectOrNull()
            if (!response.isSuccessful) {
                val detail = json?.optString("message").orEmpty()
                    .ifBlank { json?.optString("description").orEmpty() }
                    .ifBlank { "HTTP ${response.code} from $label endpoint." }
                error(detail)
            }
            return json ?: error("$label endpoint returned non-JSON content.")
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) error("URL is required.")
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val httpUrl = candidate.toHttpUrlOrNull()
            ?: error("URL must be an absolute HTTP or HTTPS URL.")
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            error("URL must use HTTP or HTTPS.")
        }
        return httpUrl.toString()
    }

    private fun convertResponseToMarkdown(
        bodyString: String,
        contentType: String,
        finalUrl: String,
    ): String {
        val trimmedBody = bodyString.trim()
        return when {
            isHtmlContent(contentType, trimmedBody) -> convertHtmlToMarkdown(trimmedBody, finalUrl)
            isStructuredTextContent(contentType, trimmedBody) -> {
                buildString {
                    append("```")
                    append('\n')
                    append(trimmedBody)
                    append('\n')
                    append("```")
                }
            }
            else -> trimmedBody
        }
    }

    private fun convertHtmlToMarkdown(
        html: String,
        finalUrl: String,
    ): String {
        val document = Jsoup.parse(html, finalUrl).apply {
            outputSettings().prettyPrint(false)
        }
        absolutizeDocumentUrls(document)

        val selectedRoot = selectContentRoot(document)
        val cleanedRoot = selectedRoot.clone().apply {
            select(
                "script,style,noscript,svg,canvas,iframe,form,input,button,nav,footer,aside," +
                    ".sidebar,.breadcrumbs,.advertisement,.ads,.social-share,[aria-hidden=true]",
            ).remove()
        }

        val title = document.title().trim()
        val markdownBody = htmlToMarkdownConverter.convert(cleanedRoot.outerHtml()).trim()
        if (title.isBlank()) {
            return markdownBody
        }
        if (markdownBody.startsWith("# ")) {
            return markdownBody
        }
        return "# $title\n\n$markdownBody".trim()
    }

    private fun selectContentRoot(document: Document): Element {
        val candidates = buildList {
            addAll(document.select("main article"))
            addAll(document.select("article"))
            addAll(document.select("main"))
            addAll(document.select("[role=main]"))
            addAll(document.select("#content"))
            addAll(document.select(".content"))
            addAll(document.select("#main-content"))
            addAll(document.select(".main-content"))
        }.filter { it.text().length >= 120 }

        return candidates.maxByOrNull { it.text().length } ?: document.body()
    }

    private fun absolutizeDocumentUrls(document: Document) {
        document.select("a[href]").forEach { anchor ->
            val absoluteUrl = anchor.absUrl("href")
            if (absoluteUrl.isNotBlank()) {
                anchor.attr("href", absoluteUrl)
            }
        }
        document.select("img[src]").forEach { image ->
            val absoluteUrl = image.absUrl("src")
            if (absoluteUrl.isNotBlank()) {
                image.attr("src", absoluteUrl)
            }
        }
    }

    private fun extractTitle(
        bodyString: String,
        contentType: String,
    ): String = when {
        isHtmlContent(contentType, bodyString) -> Jsoup.parse(bodyString).title().trim()
        else -> ""
    }

    private fun isHtmlContent(
        contentType: String,
        bodyString: String,
    ): Boolean {
        val trimmedBody = bodyString.trimStart()
        return contentType.contains("html", ignoreCase = true) ||
            trimmedBody.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmedBody.startsWith("<html", ignoreCase = true) ||
            trimmedBody.startsWith("<body", ignoreCase = true)
    }

    private fun isStructuredTextContent(
        contentType: String,
        bodyString: String,
    ): Boolean {
        val trimmedBody = bodyString.trimStart()
        return contentType.contains("json", ignoreCase = true) ||
            contentType.contains("xml", ignoreCase = true) ||
            trimmedBody.startsWith("{") ||
            trimmedBody.startsWith("[") ||
            trimmedBody.startsWith("<?xml", ignoreCase = true)
    }

    private fun normalizeMarkdown(markdown: String): String =
        markdown
            .replace("\r\n", "\n")
            .replace('\u00A0', ' ')
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()

    private fun String.truncateAtWordBoundary(maxChars: Int): String {
        if (length <= maxChars) return this
        val candidate = substring(0, maxChars)
        val lastBreak = candidate.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        val safeCutoff = if (lastBreak >= maxChars / 2) lastBreak else maxChars
        return candidate.substring(0, safeCutoff).trimEnd() + "\n\n...[truncated]"
    }

private fun String.toJsonObjectOrNull(): JSONObject? = runCatching {
        JSONObject(this)
}.getOrNull()

private fun Response.readBodyStringLimited(maxBytes: Int): String {
    val responseBody = body ?: return ""
    val declaredLength = responseBody.contentLength()
    check(declaredLength < 0L || declaredLength <= maxBytes) {
        "HTTP response is larger than the safe ${maxBytes}-byte limit."
    }
    return responseBody.byteStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(output.size() + read <= maxBytes) {
                "HTTP response exceeded the safe ${maxBytes}-byte limit."
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray().toString(Charsets.UTF_8)
    }
}

private object MetadataProtectionInterceptor : Interceptor {
    private val blockedHosts = setOf(
        "metadata.google.internal", "metadata.google.internal.", "instance-data.ec2.internal",
        "169.254.169.254", "100.100.100.200",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host.lowercase(Locale.US)
        check(host !in blockedHosts) { "Cloud metadata endpoints are not allowed." }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        check(addresses.none { it.isLinkLocalAddress || it.isMulticastAddress }) {
            "Link-local and multicast endpoints are not allowed."
        }
        return chain.proceed(chain.request())
    }
}

    private companion object {
        val JsonMediaType = "application/json".toMediaType()
    }
}
