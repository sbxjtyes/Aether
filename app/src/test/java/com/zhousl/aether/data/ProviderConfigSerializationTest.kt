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
    fun availableModelOptionsDoesNotRequireLegacyProviderId() {
        val config = LlmProviderConfig(
            id = "provider-id",
            providerId = "",
            name = "Relay",
            providerType = LlmProvider.OpenAiCompatible,
            apiKey = "",
            baseUrl = "https://relay.example/v1",
            modelId = "model-a",
        )

        val option = listOf(config).availableModelOptions().single()

        assertEquals("provider-id::model-a", option.key)
        assertEquals("Relay/model-a", option.fullLabel)
    }

    @Test
    fun duplicateProviderNamesUseHostAndStableIndexLabels() {
        val configs = listOf(
            providerConfig("first", "https://relay.example/v1").copy(
                cachedModels = listOf("model-a", "model-b"),
                enabledModelIds = listOf("model-a", "model-b"),
            ),
            providerConfig("second", "https://relay.example/v1"),
        )

        val options = configs.availableModelOptions()

        assertEquals(
            listOf(
                "Relay · relay.example/v1 · first",
                "Relay · relay.example/v1 · first",
                "Relay · relay.example/v1 · second",
            ),
            options.map { it.providerName },
        )
        assertEquals(listOf("first::model-a", "first::model-b", "second::model-a"), options.map { it.key })
    }

    @Test
    fun duplicateConfigIdsAreRepairedDuringImport() {
        val raw = JSONArray()
            .put(JSONObject().put("id", "same").put("providerType", "openai_compatible").put("baseUrl", "https://one.example/v1").put("modelId", "one"))
            .put(JSONObject().put("id", "same").put("providerType", "openai_compatible").put("baseUrl", "https://two.example/v1").put("modelId", "two"))

        val configs = parseProviderConfigs(raw.toString())

        assertEquals(2, configs.map { it.id }.distinct().size)
        assertEquals("same", configs.first().id)
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

    private fun providerConfig(id: String, baseUrl: String) = LlmProviderConfig(
        id = id,
        providerId = "legacy-$id",
        name = "Relay",
        providerType = LlmProvider.OpenAiCompatible,
        apiKey = "",
        baseUrl = baseUrl,
        modelId = "model-a",
    )
}
