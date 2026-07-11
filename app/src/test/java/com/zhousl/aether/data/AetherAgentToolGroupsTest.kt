package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherAgentToolGroupsTest {
    @Test
    fun defaultToolGroupsExposeLegacyBaseTools() {
        val names = buildAetherBaseToolDefinitions()
            .toolNames()

        assertTrue(names.contains("read"))
        assertTrue(names.contains("edit"))
        assertTrue(names.contains("write"))
        assertTrue(names.contains("grep"))
        assertTrue(names.contains("find"))
        assertTrue(names.contains("ls"))
        assertTrue(names.contains("analyze_image"))
        assertTrue(names.contains("bash"))
        assertTrue(names.contains("fetch_bash_output"))
        assertTrue(names.contains("kill_bash"))
        assertTrue(names.contains("sleep"))
        assertTrue(names.contains("fetch_web_url"))
        assertTrue(names.contains("tavily_search"))
        assertTrue(names.contains("stock_market_data"))
    }

    @Test
    fun disabledWebGroupHidesWebTools() {
        val names = buildAetherBaseToolDefinitions(
            enabledToolGroups = ChatToolGroups.DefaultEnabled - ChatToolGroups.Web,
        ).toolNames()

        assertFalse(names.contains("fetch_web_url"))
        assertFalse(names.contains("tavily_search"))
        assertFalse(names.contains("stock_market_data"))
        assertTrue(names.contains("bash"))
        assertTrue(names.contains("read"))
    }

    @Test
    fun disabledTerminalGroupHidesBashTools() {
        val names = buildAetherBaseToolDefinitions(
            enabledToolGroups = ChatToolGroups.DefaultEnabled - ChatToolGroups.Terminal,
        ).toolNames()

        assertFalse(names.contains("bash"))
        assertFalse(names.contains("fetch_bash_output"))
        assertFalse(names.contains("kill_bash"))
        assertFalse(names.contains("sleep"))
        assertTrue(names.contains("fetch_web_url"))
        assertTrue(names.contains("read"))
    }

    @Test
    fun disabledExtensionsGroupBlocksSkillAndMcpTools() {
        val enabledGroups = ChatToolGroups.DefaultEnabled - ChatToolGroups.Extensions

        assertFalse(isAetherToolAvailableForGroups("activate_skill", enabledGroups))
        assertFalse(isAetherToolAvailableForGroups("read_skill_resource", enabledGroups))
        assertFalse(isAetherToolAvailableForGroups("mcp_call_tool", enabledGroups))
        assertFalse(isAetherToolAvailableForGroups("mcp__server__tool", enabledGroups))
        assertTrue(isAetherToolAvailableForGroups("read", enabledGroups))
    }

    @Test
    fun planModeExposesOnlyReadOnlyBaseTools() {
        val names = buildAetherBaseToolDefinitions(
            agentModeEnabled = true,
            planModeEnabled = true,
        ).toolNames()

        assertTrue(names.contains("read"))
        assertTrue(names.contains("grep"))
        assertTrue(names.contains("find"))
        assertTrue(names.contains("ls"))
        assertTrue(names.contains("analyze_image"))
        assertTrue(names.contains("fetch_web_url"))
        assertTrue(names.contains("tavily_search"))
        assertTrue(names.contains("stock_market_data"))
        assertFalse(names.contains("edit"))
        assertFalse(names.contains("write"))
        assertFalse(names.contains("bash"))
        assertFalse(names.contains("fetch_bash_output"))
        assertFalse(names.contains("kill_bash"))
        assertFalse(names.contains("sleep"))
        assertFalse(names.contains("agent_display"))
    }

    @Test
    fun planModeAvailabilityBlocksMutationTerminalAgentModeAndMcpCalls() {
        assertTrue(isAetherToolAvailableForGroups("read", planModeEnabled = true))
        assertTrue(isAetherToolAvailableForGroups("run_tool_batch", planModeEnabled = true))
        assertTrue(isAetherToolAvailableForGroups("mcp_list_tools", planModeEnabled = true))
        assertTrue(isAetherToolAvailableForGroups("mcp_read_resource", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("set_conversation_status", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("update_task_state", planModeEnabled = true))

        assertFalse(isAetherToolAvailableForGroups("edit", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("write", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("bash", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("sleep", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("agent_display", agentModeEnabled = true, planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("mcp_call_tool", planModeEnabled = true))
        assertFalse(isAetherToolAvailableForGroups("mcp__server__tool", planModeEnabled = true))
    }

    @Test
    fun planModeInstructionsDescribeCodexStylePlanningFlow() {
        val instructions = buildPlanModeInstructions()

        assertTrue(instructions.contains("read-only planning agent"))
        assertTrue(instructions.contains("Treat any user task/request as something to plan, not answer or perform directly"))
        assertTrue(instructions.contains("analysis, research, writing, debugging, and finance/stock questions produce an analysis or execution plan"))
        assertTrue(instructions.contains("Do not provide the final answer, investment analysis conclusion, finished report, implementation result, or deliverable content"))
        assertTrue(instructions.contains("Phase 1 - Ground in the environment"))
        assertTrue(instructions.contains("first gather facts"))
        assertTrue(instructions.contains("Phase 2 - Clarify intent"))
        assertTrue(instructions.contains("Phase 3 - Produce the implementation plan"))
        assertTrue(instructions.contains("2-3 short mutually exclusive text options"))
        assertTrue(instructions.contains("(recommended)"))
        assertTrue(instructions.contains("execution framework decides whether the turn is complete"))
        assertTrue(instructions.contains("<proposed_plan>...</proposed_plan>"))
        assertTrue(instructions.contains("single <proposed_plan>...</proposed_plan> block"))
        assertTrue(instructions.contains("Summary, Implementation Changes, Test Plan, and Assumptions"))
        assertTrue(instructions.contains("Match the user's language"))
        assertTrue(instructions.contains("must not modify files"))
        assertTrue(instructions.contains("Do not claim that implementation has been completed"))
    }

    @Test
    fun goalModeInstructionsDescribeClosedLoopExecution() {
        val instructions = buildGoalModeInstructions()

        assertTrue(instructions.contains("GOAL MODE is enabled"))
        assertTrue(instructions.contains("current task_state goal"))
        assertTrue(instructions.contains("complete result in the current turn"))
        assertTrue(instructions.contains("execution framework exclusively decides"))
        assertFalse(instructions.contains("update_task_state"))
        assertFalse(instructions.contains("set_conversation_status"))
    }

    @Test
    fun forceToolUseRecognizesMarketRefreshRequests() {
        assertTrue(shouldForceToolUseForLatestUserText("\u5237\u65b0\u6700\u65b0\u6570\u636e\u3002"))
        assertTrue(shouldForceToolUseForLatestUserText("\u5e2e\u6211\u770b\u4e00\u4e0b\u6700\u65b0\u884c\u60c5"))
        assertTrue(shouldForceToolUseForLatestUserText("600519 \u73b0\u4ef7\u591a\u5c11"))
        assertTrue(shouldForceToolUseForLatestUserText("AAPL current price?"))
        assertFalse(shouldForceToolUseForLatestUserText("thanks for the summary"))
    }

    private fun List<JSONObject>.toolNames(): Set<String> =
        map { definition -> definition.getJSONObject("function").getString("name") }.toSet()
}
