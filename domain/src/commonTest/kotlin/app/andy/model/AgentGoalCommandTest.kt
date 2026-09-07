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

    @Test
    fun parsesAndyLoopGoalAndStripsFlags() {
        assertEquals(
            "Ship the regression fix",
            "/andy-loop Ship the regression fix".parseAndyLoopGoal(),
        )
        assertEquals(
            "keep trying until green",
            "/andy-loop --provider cursor --max-iterations 5 --max-time 30m keep trying until green"
                .parseAndyLoopGoal(),
        )
        assertEquals(
            "verify the build",
            "/andy-loop --verify-provider=claude --provider=cursor verify the build".parseAndyLoopGoal(),
        )
        assertNull("/andy-loop".parseAndyLoopGoal())
        assertNull("/andy-loop --max-iterations 3".parseAndyLoopGoal())
        assertNull("please use /andy-loop later".parseAndyLoopGoal())
    }

    @Test
    fun resolvePersistedTaskGoalPrefersNativeGoalThenLoop() {
        assertEquals(
            "native goal",
            "/goal native goal\n/andy-loop ignored".resolvePersistedTaskGoal(supportsNativeGoalCommand = true),
        )
        assertNull("/goal clear\n/andy-loop still here".resolvePersistedTaskGoal(supportsNativeGoalCommand = true))
        assertEquals(
            "loop objective",
            "/andy-loop loop objective".resolvePersistedTaskGoal(supportsNativeGoalCommand = false),
        )
        assertEquals(
            "loop objective",
            "/andy-loop loop objective".resolvePersistedTaskGoal(supportsNativeGoalCommand = true),
        )
    }
}
