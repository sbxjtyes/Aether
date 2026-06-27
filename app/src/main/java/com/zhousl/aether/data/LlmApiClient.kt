package com.zhousl.aether.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LlmApiClient {
    private val httpClient: OkHttpClient = buildDefaultLlmHttpClient()
        .newBuilder()
        .connectTimeout(15_000, TimeUnit.MILLISECONDS)
        .readTimeout(30_000, TimeUnit.MILLISECONDS)
        .callTimeout(30_000, TimeUnit.MILLISECONDS)
        .build()

    data class FetchModelsResult(
        val models: List<String>,
        val error: String? = null,
    )

    suspend fun fetchModels(config: LlmProviderConfig): FetchModelsResult = withContext(Dispatchers.IO) {
        try {
            when (config.providerType) {
                LlmProvider.OpenAiResponses -> fetchOpenAiModels(config)
                LlmProvider.OpenAiCompatible -> fetchOpenAiModels(config)
                LlmProvider.VertexExpress -> fetchVertexModels(config)
                LlmProvider.AnthropicMessages -> fetchAnthropicModels(config)
            }
        } catch (e: Exception) {
            FetchModelsResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    private suspend fun fetchOpenAiModels(config: LlmProviderConfig): FetchModelsResult {
        val baseUrl = config.baseUrl.trimEnd('/')
        val modelsUrl = when {
            baseUrl.endsWith("/responses") -> baseUrl.replace("/responses", "/models")
            baseUrl.endsWith("/chat/completions") -> baseUrl.replace("/chat/completions", "/models")
            baseUrl.endsWith("/v1") -> "$baseUrl/models"
            else -> "$baseUrl/models"
        }

        val request = Request.Builder()
            .url(modelsUrl)
            .header("User-Agent", config.resolvedUserAgent())
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .get()
            .build()
        val response = executeModelsRequest(request)

        return if (response.code == 200) {
            val models = parseModelIds(response.body)
            // Sort models: prefer chat/gpt models first
            val sortedModels = models.sortedWith(
                compareBy(
                    { if (it.contains("gpt") || it.contains("chat")) 0 else 1 },
                    { it },
                )
            )
            FetchModelsResult(sortedModels)
        } else {
            FetchModelsResult(emptyList(), response.body.ifBlank { "HTTP ${response.code}" })
        }
    }

    private fun fetchVertexModels(config: LlmProviderConfig): FetchModelsResult {
        // Vertex AI Express Mode doesn't have a simple models list endpoint
        // Return commonly used Gemini models
        val previewModels = if (usesOfficialVertexEndpoint(config.providerType, config.baseUrl)) {
            OfficialVertexPreviewModels
        } else {
            emptyList()
        }
        val defaultModels = previewModels + listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-pro",
        )
        return FetchModelsResult(defaultModels)
    }

    private suspend fun fetchAnthropicModels(config: LlmProviderConfig): FetchModelsResult {
        val baseUrl = config.baseUrl.trimEnd('/')
        val modelsUrl = when {
            baseUrl.endsWith("/messages") -> baseUrl.replace("/messages", "/models")
            baseUrl.endsWith("/v1") -> "$baseUrl/models"
            else -> "$baseUrl/models"
        }

        val request = Request.Builder()
            .url(modelsUrl)
            .header("User-Agent", config.resolvedUserAgent())
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .get()
            .build()
        val response = executeModelsRequest(request)

        return if (response.code == 200) {
            val models = parseModelIds(response.body)
            if (models.isEmpty()) {
                FetchModelsResult(defaultAnthropicModels())
            } else {
                FetchModelsResult(models.sorted())
            }
        } else {
            FetchModelsResult(defaultAnthropicModels(), response.body.ifBlank { "HTTP ${response.code}" })
        }
    }

    private suspend fun executeModelsRequest(request: Request): ModelsHttpResponse =
        executeLlmCallWithTlsFallback(httpClient, request) { client, candidateRequest ->
            client.newCall(candidateRequest).execute().use { response ->
                ModelsHttpResponse(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                )
            }
        }

    private fun parseModelIds(responseText: String): List<String> {
        val json = JSONObject(responseText)
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val models = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val modelObj = dataArray.optJSONObject(i)
            val modelId = modelObj?.optString("id")
            if (!modelId.isNullOrBlank()) {
                models.add(modelId)
            }
        }
        return models
    }

    private fun defaultAnthropicModels(): List<String> = listOf(
        "claude-opus-4-5",
        "claude-sonnet-4-5",
        "claude-haiku-4-5",
    )

    private data class ModelsHttpResponse(
        val code: Int,
        val body: String,
    )
}

internal fun LlmProviderConfig.resolvedUserAgent(): String =
    userAgent.toSafeHttpHeaderValue().ifBlank { DefaultLlmUserAgent }
