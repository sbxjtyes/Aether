package com.zhousl.aether.data

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val DefaultFetchMarkdownChars = 20_000
private const val MinFetchMarkdownChars = 500
private const val MaxFetchMarkdownChars = 100_000
private const val DefaultUserAgent =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
private const val DefaultTavilyBaseUrl = "https://api.tavily.com/"
private const val DefaultStockSearchBaseUrl = "https://searchapi.eastmoney.com/"
private const val DefaultStockQuoteBaseUrl = "https://push2delay.eastmoney.com/"
private const val DefaultStockKlineBaseUrl = "https://push2his.eastmoney.com/"
private const val EastmoneySuggestToken = "D43BF722C8E33BDC906FB84D85E326E8"
private const val EastmoneyQuoteFields =
    "f43,f44,f45,f46,f47,f48,f49,f57,f58,f60,f84,f85,f86,f107,f116,f117,f152"

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

data class StockChartRequest(
    val symbol: String,
    val range: String = "1d",
    val interval: String = "1m",
    val includePrePost: Boolean = false,
)

class WebToolsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val tavilyBaseUrl: String = DefaultTavilyBaseUrl,
    private val stockBaseUrl: String = "",
    private val stockSearchBaseUrl: String = DefaultStockSearchBaseUrl,
    private val stockQuoteBaseUrl: String = DefaultStockQuoteBaseUrl,
    private val stockKlineBaseUrl: String = DefaultStockKlineBaseUrl,
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
                val bodyString = response.body?.string().orEmpty()
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
                val bodyString = response.body?.string().orEmpty()
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

            val resolvedSymbol = resolveEastmoneySecid(symbol)
            val quoteEndpoint = buildStockEndpoint(resolveStockBaseUrl(stockQuoteBaseUrl), "api", "qt", "stock", "get") {
                addQueryParameter("secid", resolvedSymbol.secid)
                addQueryParameter("fields", EastmoneyQuoteFields)
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

    private fun resolveStockBaseUrl(defaultBaseUrl: String): String =
        stockBaseUrl.trim().ifBlank { defaultBaseUrl }

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
            val bodyString = response.body?.string().orEmpty()
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

    private companion object {
        val JsonMediaType = "application/json".toMediaType()
    }
}
