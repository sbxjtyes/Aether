package com.zhousl.aether.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigSerializationTest {
    @Test
    fun importedProviderConfigBackfillsMissingNameAndBaseUrl() {
        val configs = parseProviderConfigs(
            JSONArray().put(
                JSONObject()
                    .put("providerType", LlmProvider.AnthropicMessages.storageValue)
                    .put("name", "")
                    .put("baseUrl", "")
                    .put("modelId", "claude-test")
            ).toString()
        )

        val config = configs.single()
        assertEquals(LlmProvider.AnthropicMessages.displayName, config.name)
        assertEquals(LlmProvider.AnthropicMessages.defaultBaseUrl, config.baseUrl)

        val option = configs.availableModelOptions().single()
        assertEquals(LlmProvider.AnthropicMessages.defaultBaseUrl, option.baseUrl)
        assertEquals("claude-test", option.modelId)
    }

    @Test
    fun providerIdAcceptsLongGeneratedNames() {
        val longProviderName = "Provider " + (1..80).joinToString(" ") { "segment$it" }
        val providerId = longProviderName.sanitizeProviderId()

        assertTrue(providerId.length > 256)
        assertTrue(isValidProviderId(providerId))

        val config = LlmProviderConfig(
            id = "long-provider",
            providerId = providerId,
            name = longProviderName,
            providerType = LlmProvider.OpenAiCompatible,
            apiKey = "",
            baseUrl = "https://long-provider.example/v1",
            modelId = "model-a",
        )

        val restoredConfig = parseProviderConfigs(serializeProviderConfigs(listOf(config))).single()
        assertEquals(providerId, restoredConfig.providerId)
        assertEquals(providerId, listOf(restoredConfig).availableModelOptions().single().providerId)
    }

    @Test
    fun availableModelOptionsSkipsConfigsWithBlankBaseUrl() {
        val options = listOf(
            LlmProviderConfig(
                id = "bad-provider",
                providerId = "bad",
                name = "Bad",
                providerType = LlmProvider.OpenAiCompatible,
                apiKey = "",
                baseUrl = "",
                modelId = "model-a",
            )
        ).availableModelOptions()

        assertTrue(options.isEmpty())
    }
}
