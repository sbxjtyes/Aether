package com.zhousl.aether.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.zhousl.aether.data.LlmProvider
import com.zhousl.aether.data.LlmProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderFormStateSaverTest {
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun `saved state excludes provider api key`() {
        val existingConfig = providerConfig(apiKey = "persisted-secret")
        val state = ProviderFormState.fromConfig(existingConfig).apply {
            apiKey = "edited-secret"
        }

        val saved = providerFormStateSaver(existingConfig).saveState(state) as List<*>

        assertEquals("", saved[2])
        assertFalse(saved.contains("persisted-secret"))
        assertFalse(saved.contains("edited-secret"))
    }

    @Test
    fun `restore ignores legacy saved api key and uses persisted config`() {
        val existingConfig = providerConfig(apiKey = "persisted-secret")
        val saved = (
            providerFormStateSaver(existingConfig)
                .saveState(ProviderFormState.fromConfig(existingConfig)) as List<*>
            ).toMutableList()
        saved[2] = "legacy-saved-secret"

        val restored = providerFormStateSaver(existingConfig).restore(saved as Any)

        assertEquals("persisted-secret", restored?.apiKey)
    }

    @Test
    fun `restore drops unsaved api key for a new provider`() {
        val state = ProviderFormState.fromConfig(existingConfig = null).apply {
            apiKey = "unsaved-secret"
        }
        val saver = providerFormStateSaver(existingConfig = null)
        val saved = saver.saveState(state)

        val restored = saver.restore(saved)

        assertEquals("", restored?.apiKey)
    }

    private fun Saver<ProviderFormState, Any>.saveState(state: ProviderFormState): Any =
        with(this) {
            with(saverScope) {
                save(state)
            }
        } ?: error("Provider form state was not saveable")

    private fun providerConfig(apiKey: String) = LlmProviderConfig(
        id = "provider-id",
        providerId = "openai",
        name = "OpenAI",
        providerType = LlmProvider.OpenAiCompatible,
        apiKey = apiKey,
        baseUrl = "https://api.example.com/v1",
        modelId = "model-id",
    )
}
