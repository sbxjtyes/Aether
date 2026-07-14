package com.zhousl.aether.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSettingsTest {
    @Test
    fun `voice server requires url token and voice id`() {
        val configured = AppSettings(
            voiceServerBaseUrl = "https://voice.example.com",
            voiceServerToken = "secret",
            voiceId = "qixia",
        )

        assertTrue(configured.hasConfiguredVoiceServer())
        assertFalse(configured.copy(voiceServerBaseUrl = " ").hasConfiguredVoiceServer())
        assertFalse(configured.copy(voiceServerToken = "").hasConfiguredVoiceServer())
        assertFalse(configured.copy(voiceId = "").hasConfiguredVoiceServer())
    }

    @Test
    fun `voice speed is constrained to supported range`() {
        assertEquals(75, normalizeVoiceSpeedPercent(10))
        assertEquals(100, normalizeVoiceSpeedPercent(null))
        assertEquals(125, normalizeVoiceSpeedPercent(500))
    }
}
