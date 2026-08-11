package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MergedComposerSlashCommandsTest {
    @Test
    fun normalizesCodexSkillInvocationTokenToAndysSlashSyntax() {
        assertEquals("design-taste-frontend", "${'$'}design-taste-frontend".composerCommandName())
        assertEquals("/design-taste-frontend", "${'$'}design-taste-frontend".composerCommandToken())
        assertEquals("/goal", "goal".composerCommandToken())
        assertEquals("/review", "/review".composerCommandToken())
    }

    @Test
    fun mergesAndyNativeGoalWithProviderCommandsWithoutDuplicates() {
        val merged = mergedComposerSlashCommands(
            agent = AgentKind.ClaudeCode,
            providerCommands = listOf(
                AgentSlashCommand("loop", "run a prompt on an interval"),
                AgentSlashCommand("goal", "provider copy should lose to Andy native"),
            ),
        )
        assertEquals(
            listOf("goal", "loop"),
            merged.map { it.name },
        )
        assertEquals("set or clear this task's persistent goal", merged.first { it.name == "goal" }.description)
    }

    @Test
    fun slashMenuHidesDiskSkillsAlreadyAdvertisedAsProviderCommands() {
        val commands = listOf(
            AgentNativeSlashCommand("babysit", "Keep a PR merge-ready"),
            AgentNativeSlashCommand("run", "launch and drive your app"),
        )
        val skills = listOf(
            AgentSkill("babysit", "", "/skills/babysit/SKILL.md"),
            AgentSkill("compose-expert", "Compose UI guidance", "/skills/compose-expert/SKILL.md"),
        )
        assertEquals(
            listOf("compose-expert"),
            composerSkillsForSlashMenu(skills, commands).map { it.name },
        )
    }

    @Test
    fun slashMenuHidesNonUserInvocableSkills() {
        val skills = listOf(
            AgentSkill("andy-orchestration", "reference", "/skills/andy-orchestration/SKILL.md", userInvocable = false),
            AgentSkill("andy-handoff", "handoff", "/skills/andy-handoff/SKILL.md", userInvocable = true),
        )
        assertEquals(
            listOf("andy-handoff"),
            composerSkillsForSlashMenu(skills, commands = emptyList()).map { it.name },
        )
    }
}
