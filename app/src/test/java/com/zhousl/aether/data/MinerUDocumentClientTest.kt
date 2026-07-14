package com.zhousl.aether.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MinerUDocumentClientTest {
    @Test
    fun openEndedFullDocumentRangeIsOmitted() {
        assertEquals("", normalizeMinerUPageRange("1-"))
        assertEquals("", normalizeMinerUPageRange(""))
    }

    @Test
    fun closedPageRangesAreValidatedBeforeNetworkUse() {
        assertEquals("5", normalizeMinerUPageRange(" 5 "))
        assertEquals("1-200", normalizeMinerUPageRange("1-200"))
        val invalidOpenRange = runCatching { normalizeMinerUPageRange("2-") }.exceptionOrNull()
        val reversedRange = runCatching { normalizeMinerUPageRange("10-2") }.exceptionOrNull()
        assertTrue(invalidOpenRange?.message.orEmpty().contains("closed range"))
        assertTrue(reversedRange?.message.orEmpty().contains("must not be greater"))
    }

    @Test
    fun cancellationDoesNotStartLightweightFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/").toString().removeSuffix("/")
            val pollObserved = CountDownLatch(1)
            val lightweightCreates = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v4/file-urls/batch" -> json(
                        """{"code":0,"data":{"batch_id":"batch-cancel","file_urls":["$root/upload"]}}"""
                    )
                    "/upload" -> MockResponse().setResponseCode(200)
                    "/api/v4/extract-results/batch/batch-cancel" -> {
                        pollObserved.countDown()
                        json("""{"code":0,"data":{"extract_result":[{"state":"running"}]}}""")
                    }
                    "/api/v1/agent/parse/file" -> {
                        lightweightCreates.incrementAndGet()
                        MockResponse().setResponseCode(500)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val job = launch(Dispatchers.Default) { client(server).parsePayload(payload(), apiToken = "secret") }
            assertTrue(pollObserved.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertEquals(0, lightweightCreates.get())
        }
    }

    @Test
    fun configuredTokenUsesPreciseParser() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/").toString().removeSuffix("/")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v4/file-urls/batch" -> json(
                        """{"code":0,"data":{"batch_id":"batch-1","file_urls":["$root/upload"]}}"""
                    )
                    "/upload" -> MockResponse().setResponseCode(200)
                    "/api/v4/extract-results/batch/batch-1" -> json(
                        """{"code":0,"data":{"extract_result":[{"state":"done","full_zip_url":"$root/result.zip"}]}}"""
                    )
                    "/result.zip" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(markdownZip("# Precise")))
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val output = JSONObject(client(server).parsePayload(payload(), apiToken = "secret"))
            assertEquals("precise", output.getString("parser_mode"))
            assertEquals("# Precise", output.getString("markdown"))
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun preciseFailureFallsBackToLightweightParser() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val root = server.url("/").toString().removeSuffix("/")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v4/file-urls/batch" -> json("""{"code":401,"msg":"invalid token"}""")
                    "/api/v1/agent/parse/file" -> json(
                        """{"code":0,"data":{"task_id":"task-1","file_url":"$root/upload"}}"""
                    )
                    "/upload" -> MockResponse().setResponseCode(200)
                    "/api/v1/agent/parse/task-1" -> json(
                        """{"code":0,"data":{"state":"done","markdown_url":"$root/result.md"}}"""
                    )
                    "/result.md" -> MockResponse().setResponseCode(200).setBody("# Lightweight")
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val output = JSONObject(client(server).parsePayload(payload(), apiToken = "bad-token"))
            assertEquals("lightweight", output.getString("parser_mode"))
            assertTrue(output.getBoolean("fell_back_from_precise"))
            assertTrue(output.getString("precise_error").contains("invalid token"))
            assertEquals("# Lightweight", output.getString("markdown"))
        }
    }

    @Test
    fun oversizedFileCannotUseLightweightFallbackAfterPreciseFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"code":401,"msg":"invalid token"}"""))
            server.start()
            val oversized = WorkspaceFilePayload(
                absolutePath = "/workspace/uploads/large.pdf",
                bytes = ByteArray(10 * 1024 * 1024 + 1),
                sizeBytes = 10L * 1024 * 1024 + 1,
            )
            val failure = runCatching { client(server).parsePayload(oversized, apiToken = "bad-token") }
                .exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("exceeds the 10 MB fallback limit"))
            assertEquals(1, server.requestCount)
        }
    }

    private fun client(server: MockWebServer) = MinerUDocumentClient(
        httpClient = OkHttpClient(),
        agentBaseUrl = server.url("/api/v1/agent").toString().removeSuffix("/"),
        preciseBaseUrl = server.url("/api/v4").toString().removeSuffix("/"),
    )

    private fun payload() = WorkspaceFilePayload(
        absolutePath = "/workspace/uploads/document.pdf",
        bytes = "%PDF-test".toByteArray(),
        sizeBytes = 9,
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun markdownZip(markdown: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("full.md"))
            zip.write(markdown.toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
