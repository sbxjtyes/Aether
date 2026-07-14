package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientResponseLimitTest {
    @Test
    fun boundedReaderAcceptsResponseAtLimit() {
        val body = "1234".toResponseBody("application/json".toMediaType())

        assertEquals("1234", body.readUtf8WithLimit(4L, "test response"))
    }

    @Test
    fun boundedReaderRejectsChunkedResponseBeyondLimit() {
        val body = "12345".toResponseBody("application/json".toMediaType())

        val failure = runCatching {
            body.readUtf8WithLimit(4L, "test response")
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("4-byte safety limit"))
    }

    @Test
    fun nonStreamingRequestRejectsOversizedChunkedErrorBody() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "text/plain")
                .setChunkedBody("x".repeat(MaxLlmErrorResponseBytes.toInt() + 1), 8_192)
        )
        server.start()

        try {
            val settings = testSettings(server)
            val result = OpenAiCompatibleClient().createChatCompletion(
                settings = settings,
                systemPrompt = "",
                conversation = listOf(JSONObject().put("role", "user").put("content", "hello")),
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("1 MiB safety limit"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streamingRequestRejectsOversizedErrorBodyWithoutTreatingItAsSse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Content-Type", "text/event-stream")
                .setChunkedBody("x".repeat(MaxLlmErrorResponseBytes.toInt() + 1), 8_192)
        )
        server.start()

        try {
            val result = OpenAiCompatibleClient().streamChatCompletion(
                settings = testSettings(server),
                systemPrompt = "",
                conversation = listOf(JSONObject().put("role", "user").put("content", "hello")),
            )

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("1 MiB safety limit"))
        } finally {
            server.shutdown()
        }
    }

    private fun testSettings(server: MockWebServer) = AppSettings(
        provider = LlmProvider.OpenAiCompatible,
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        modelId = "test-model",
        llmInactivityReconnectTimeoutSeconds = 5,
    )
}
