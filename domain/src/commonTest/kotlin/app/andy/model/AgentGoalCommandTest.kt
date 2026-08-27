package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentGoalCommandTest {
    @Test
    fun parsesGoalAndKeepsFollowingPrompt() {
        assertEquals(
            AgentGoalCommand(
                action = AgentGoalCommandAction.Set,
                goal = "Ship the regression fix",
                remainingPrompt = "Start with the failing desktop test.",
            ),
            "/goal Ship the regression fix\nStart with the failing desktop test.".parseAgentGoalCommand(),
        )
    }

    @Test
    fun parsesClearGoal() {
        assertEquals(
            AgentGoalCommand(AgentGoalCommandAction.Clear),
            "/goal clear".parseAgentGoalCommand(),
        )
    }

    @Test
    fun leavesOtherSlashCommandsAlone() {
        assertNull("/goals keep working".parseAgentGoalCommand())
        assertNull("please use /goal in the reply".parseAgentGoalCommand())
    }

    @Test
    fun appendsGoalHintToProviderPrompt() {
        assertEquals(
            "Implement the fix\n\nPersistent task goal: Ship the regression fix\nKeep this goal in mind throughout the task.",
            promptWithGoalHint("Implement the fix", "Ship the regression fix"),
        )
    }

    @Test
    fun goalIsOnlyOfferedByProvidersWithNativeAndySupport() {
        assertEquals(listOf("goal"), AgentNativeSlashCommands.forAgent(AgentKind.Codex).map { it.name })
        assertEquals(listOf("goal"), AgentNativeSlashCommands.forAgent(AgentKind.ClaudeCode).map { it.name })
        assertEquals(emptyList(), AgentNativeSlashCommands.forAgent(AgentKind.Cursor))
    }
}
