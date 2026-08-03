package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrillMeInstallCommandTest {
    @Test
    fun targetsEachProviderGlobalSkillDirectory() {
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent claude-code",
            AgentKind.ClaudeCode.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent codex",
            AgentKind.Codex.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent cursor",
            AgentKind.Cursor.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent antigravity-cli",
            AgentKind.Antigravity.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent opencode",
            AgentKind.OpenCode.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --skill grilling --global --agent pi",
            AgentKind.Pi.grillMeInstallCommand(),
        )
    }

    @Test
    fun interactivePromptMentionsInterviewPlanAndQuestionArtifact() {
        val prompt = grillMeInteractivePromptAddendum(".andy/<taskId>")
        assertTrue("interview" in prompt)
        assertTrue("question.json" in prompt)
        assertTrue("plan.md" in prompt)
        assertTrue("even though plan mode is active" in prompt.lowercase())
        assertTrue("provider's default plan path" in prompt)
        assertTrue("decisions so far" in prompt)
        assertTrue("one decision question at a time" in prompt)
    }

    @Test
    fun chatPromptAddendumRoutesDecisionsThroughQuestionArtifact() {
        val prompt = grillMeChatPromptAddendum(".andy/task-42")
        assertTrue("question.json" in prompt)
        assertTrue("`.andy/task-42/question.json`" in prompt)
        assertTrue("Do not output a full implementation plan" in prompt)
    }

    @Test
    fun hasGrillMeSkillsDetectsPortableSkillNames() {
        val skills = listOf(
            AgentSkill("grill-me", "", "/tmp/grill-me/SKILL.md"),
            AgentSkill("other", "", "/tmp/other/SKILL.md"),
        )
        assertTrue(hasGrillMeSkills(skills))
        assertTrue(hasGrillMeSkills(listOf(AgentSkill("grilling", "", "/tmp/grilling/SKILL.md"))))
        assertTrue(!hasGrillMeSkills(listOf(AgentSkill("lint", "", "/tmp/lint/SKILL.md"))))
    }

    @Test
    fun specPlanWriteInstructionOverridesPlanModePath() {
        val prompt = specPlanWriteInstruction(
            ".andy/task-1",
            including = "including interfaces, edge cases, and verification steps",
        )
        assertTrue(prompt.startsWith("Even though plan mode is active"))
        assertTrue("`.andy/task-1/plan.md`" in prompt)
        assertTrue("including interfaces, edge cases, and verification steps" in prompt)
        assertTrue("Do not implement the plan." in prompt)
    }
}
