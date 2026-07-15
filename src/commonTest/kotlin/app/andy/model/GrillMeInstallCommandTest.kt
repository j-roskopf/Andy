package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GrillMeInstallCommandTest {
    @Test
    fun targetsEachProviderGlobalSkillDirectory() {
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --global --agent claude-code",
            AgentKind.ClaudeCode.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --global --agent codex",
            AgentKind.Codex.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --global --agent cursor",
            AgentKind.Cursor.grillMeInstallCommand(),
        )
        assertEquals(
            "npx skills add mattpocock/skills --skill grill-me --global --agent antigravity-cli",
            AgentKind.Antigravity.grillMeInstallCommand(),
        )
    }
}
