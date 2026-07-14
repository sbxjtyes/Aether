package com.zhousl.aether.ui

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LlmProvider
import com.zhousl.aether.data.LlmProviderConfig
import com.zhousl.aether.data.McpKeyValue
import com.zhousl.aether.data.McpServerConfig
import com.zhousl.aether.data.McpTransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPreservationTest {
    @Test
    fun redactedSettingsKeepCredentialsAlreadyOnDevice() {
        val imported = AppSettings(apiKey = "", tavilyApiKey = "", mineruApiToken = "")
        val current = AppSettings(apiKey = "llm", tavilyApiKey = "tavily", mineruApiToken = "mineru")

        val merged = preserveAppCredentials(imported, current)

        assertEquals("llm", merged.apiKey)
        assertEquals("tavily", merged.tavilyApiKey)
        assertEquals("mineru", merged.mineruApiToken)
    }

    @Test
    fun redactedProviderConfigMatchesStableProviderId() {
        val imported = provider(id = "new-export-id", providerId = "openai-main", apiKey = "")
        val current = provider(id = "local-id", providerId = "openai-main", apiKey = "secret")

        val merged = preserveProviderCredentials(listOf(imported), listOf(current)).single()

        assertEquals("secret", merged.apiKey)
        assertEquals("new-export-id", merged.id)
    }

    @Test
    fun redactedMcpConfigKeepsHeadersAndEnvironment() {
        val currentHttp = McpServerConfig(
            id = "http",
            displayName = "HTTP",
            transport = McpTransportConfig.StreamableHttp(
                url = "https://old.example",
                headers = listOf(McpKeyValue("Authorization", "Bearer secret")),
            ),
        )
        val importedHttp = currentHttp.copy(
            displayName = "Updated HTTP",
            transport = McpTransportConfig.StreamableHttp(url = "https://old.example/"),
        )
        val currentStdio = McpServerConfig(
            id = "stdio",
            displayName = "stdio",
            transport = McpTransportConfig.StdIo(
                command = "server",
                environment = listOf(McpKeyValue("API_KEY", "secret")),
            ),
        )
        val importedStdio = currentStdio.copy(
            transport = McpTransportConfig.StdIo(command = "server"),
        )

        val merged = preserveMcpCredentials(
            listOf(importedHttp, importedStdio),
            listOf(currentHttp, currentStdio),
        )

        assertEquals("Bearer secret", (merged[0].transport as McpTransportConfig.StreamableHttp).headers.single().value)
        assertEquals("https://old.example/", (merged[0].transport as McpTransportConfig.StreamableHttp).url)
        assertEquals("secret", (merged[1].transport as McpTransportConfig.StdIo).environment.single().value)
        assertEquals("server", (merged[1].transport as McpTransportConfig.StdIo).command)
    }

    @Test
    fun changedDestinationsNeverReceiveLocalCredentials() {
        val currentSettings = AppSettings(apiKey = "secret", baseUrl = "https://trusted.example/v1")
        val importedSettings = currentSettings.copy(apiKey = "", baseUrl = "https://attacker.example/v1")
        assertEquals("", preserveAppCredentials(importedSettings, currentSettings).apiKey)

        val currentProvider = provider("provider", "stable", "secret")
        val importedProvider = currentProvider.copy(apiKey = "", baseUrl = "https://attacker.example/v1")
        assertEquals(
            "",
            preserveProviderCredentials(listOf(importedProvider), listOf(currentProvider)).single().apiKey,
        )

        val currentHttp = McpServerConfig(
            id = "http",
            displayName = "HTTP",
            transport = McpTransportConfig.StreamableHttp(
                url = "https://trusted.example",
                headers = listOf(McpKeyValue("Authorization", "Bearer secret")),
            ),
        )
        val importedHttp = currentHttp.copy(
            transport = McpTransportConfig.StreamableHttp(url = "https://attacker.example"),
        )
        val currentStdio = McpServerConfig(
            id = "stdio",
            displayName = "stdio",
            transport = McpTransportConfig.StdIo(
                command = "trusted-server",
                environment = listOf(McpKeyValue("API_KEY", "secret")),
            ),
        )
        val importedStdio = currentStdio.copy(
            transport = McpTransportConfig.StdIo(command = "attacker-server"),
        )

        val merged = preserveMcpCredentials(
            listOf(importedHttp, importedStdio),
            listOf(currentHttp, currentStdio),
        )
        assertTrue((merged[0].transport as McpTransportConfig.StreamableHttp).headers.isEmpty())
        assertTrue((merged[1].transport as McpTransportConfig.StdIo).environment.isEmpty())
    }

    private fun provider(id: String, providerId: String, apiKey: String) = LlmProviderConfig(
        id = id,
        providerId = providerId,
        name = providerId,
        providerType = LlmProvider.OpenAiCompatible,
        apiKey = apiKey,
        baseUrl = "https://api.example.com/v1",
        modelId = "model",
    )
}
