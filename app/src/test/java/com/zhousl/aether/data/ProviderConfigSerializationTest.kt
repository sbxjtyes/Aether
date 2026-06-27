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
            userAgent = "Allowed-Client/1.0",
        )

        val restoredConfig = parseProviderConfigs(serializeProviderConfigs(listOf(config))).single()
        assertEquals(providerId, restoredConfig.providerId)
        assertEquals("Allowed-Client/1.0", restoredConfig.userAgent)
        val option = listOf(restoredConfig).availableModelOptions().single()
        assertEquals(providerId, option.providerId)
        assertEquals("Allowed-Client/1.0", option.userAgent)
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

    @Test
    fun invalidUserAgentFallsBackToBrowserDefault() {
        val config = LlmProviderConfig(
            id = "provider",
            providerId = "provider",
            name = "Provider",
            providerType = LlmProvider.OpenAiCompatible,
            apiKey = "",
            baseUrl = "https://provider.example/v1",
            modelId = "model-a",
            userAgent = "公益站Client/1.0",
        )

        assertEquals(DefaultLlmUserAgent, config.resolvedUserAgent())
    }
}
