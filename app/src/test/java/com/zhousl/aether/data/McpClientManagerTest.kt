package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody

class McpClientManagerTest {
    @Test
    fun limitedMcpResponseReader_acceptsLimitAndRejectsLargerBodies() {
        assertEquals("1234", "1234".toResponseBody().readUtf8Limited(4))
        assertThrows(IllegalStateException::class.java) {
            "12345".toResponseBody().readUtf8Limited(4)
        }
    }

    @Test
    fun mcpToolBinding_exposesProviderSafeNamespacedToolName() {
        val binding = McpToolBinding(
            serverId = "mcp-server.with symbols/and a very very very long id",
            serverName = "Server",
            toolName = "tool name/with symbols:and a very very very long name",
            description = "",
            inputSchema = JSONObject(),
        )

        val callName = binding.namespacedToolName

        assertTrue(callName.length <= 64)
        assertTrue(callName.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")))
        assertTrue(binding.matchesToolCallName(callName))
        assertTrue(binding.matchesToolCallName(binding.rawNamespacedToolName))
    }

    @Test
    fun safeMcpToolCallNamesKeepSanitizedCollisionsDistinct() {
        val hyphenated = buildSafeMcpToolCallName("server-a", "read-file")
        val underscored = buildSafeMcpToolCallName("server_a", "read_file")

        assertNotEquals(hyphenated, underscored)
    }

    @Test
    fun supportsMcpServerCapability_onlyAdvertisedCapabilitiesAreEnabled() {
        val capabilities = JSONObject(
            """
            {
              "tools": {}
            }
            """.trimIndent(),
        )

        assertTrue(supportsMcpServerCapability(capabilities, "tools"))
        assertFalse(supportsMcpServerCapability(capabilities, "resources"))
        assertFalse(supportsMcpServerCapability(capabilities, "prompts"))
    }

    @Test
    fun supportsMcpServerCapability_defaultsToToolsWhenCapabilitiesMissing() {
        assertTrue(supportsMcpServerCapability(null, "tools"))
        assertFalse(supportsMcpServerCapability(null, "resources"))
    }

    @Test
    fun isMcpMethodNotFoundError_detectsJsonRpcMethodMissing() {
        assertTrue(isMcpMethodNotFoundError(IllegalStateException("Method not found")))
        assertFalse(isMcpMethodNotFoundError(IllegalStateException("Connection refused")))
    }
}
