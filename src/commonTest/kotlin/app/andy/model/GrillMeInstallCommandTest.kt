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
    }

    @Test
    fun interactivePromptMentionsInterviewAndPlan() {
        val prompt = grillMeInteractivePromptAddendum(".andy/<taskId>")
        assertTrue("interview" in prompt)
        assertTrue("plan.md" in prompt)
    }
}
