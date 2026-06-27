package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmApiClientTest {
    @Test
    fun openAiFetchModelsUsesModelsEndpointAndHeaders() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "embedding-model" },
                        { "id": "gpt-test" },
                        { "id": "chat-test" }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val result = LlmApiClient.fetchModels(
                LlmProviderConfig(
                    providerId = "openai_compatible",
                    name = "Relay",
                    providerType = LlmProvider.OpenAiCompatible,
                    apiKey = "test-key",
                    baseUrl = server.url("/v1/chat/completions").toString(),
                    modelId = "gpt-test",
                    userAgent = "Allowed-Client/1.0",
                )
            )

            assertEquals(null, result.error)
            assertEquals(listOf("chat-test", "gpt-test", "embedding-model"), result.models)

            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals("Allowed-Client/1.0", request.getHeader("User-Agent"))
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Content-Type"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicFetchModelsUsesModelsEndpointAndHeaders() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "claude-b" },
                        { "id": "claude-a" }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()

        try {
            val result = LlmApiClient.fetchModels(
                LlmProviderConfig(
                    providerId = "anthropic",
                    name = "Anthropic",
                    providerType = LlmProvider.AnthropicMessages,
                    apiKey = "test-key",
                    baseUrl = server.url("/v1/messages").toString(),
                    modelId = "claude-a",
                )
            )

            assertEquals(null, result.error)
            assertEquals(listOf("claude-a", "claude-b"), result.models)

            val request = server.takeRequest()
            assertEquals("/v1/models", request.path)
            assertEquals(DefaultLlmUserAgent, request.getHeader("User-Agent"))
            assertEquals("test-key", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            assertEquals("application/json", request.getHeader("Content-Type"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vertexOfficialEndpointIncludesPreviewModels() = runBlocking {
        val result = LlmApiClient.fetchModels(
            LlmProviderConfig(
                providerId = "vertex",
                name = "Vertex",
                providerType = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = "https://aiplatform.googleapis.com/v1/",
                modelId = "gemini-2.5-flash",
            )
        )

        assertEquals(null, result.error)
        assertTrue(result.models.contains("gemini-3.1-pro-preview"))
        assertTrue(result.models.contains("gemini-3-flash-preview"))
        assertTrue(result.models.contains("gemini-3.1-flash-lite-preview"))
        assertTrue(result.models.indexOf("gemini-3.1-pro-preview") < result.models.indexOf("gemini-2.5-pro"))
    }

    @Test
    fun vertexRegionalOfficialEndpointIncludesPreviewModels() = runBlocking {
        val result = LlmApiClient.fetchModels(
            LlmProviderConfig(
                providerId = "vertex",
                name = "Vertex",
                providerType = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = "https://us-central1-aiplatform.googleapis.com/v1",
                modelId = "gemini-2.5-flash",
            )
        )

        assertTrue(result.models.contains("gemini-3.1-flash-lite-preview"))
    }

    @Test
    fun vertexCustomEndpointDoesNotIncludeOfficialPreviewPatch() = runBlocking {
        val result = LlmApiClient.fetchModels(
            LlmProviderConfig(
                providerId = "vertex",
                name = "Vertex",
                providerType = LlmProvider.VertexExpress,
                apiKey = "test-key",
                baseUrl = "https://vertex-proxy.example.com/v1",
                modelId = "gemini-2.5-flash",
            )
        )

        assertFalse(result.models.contains("gemini-3.1-pro-preview"))
        assertFalse(result.models.contains("gemini-3-flash-preview"))
        assertFalse(result.models.contains("gemini-3.1-flash-lite-preview"))
    }
}
