package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillRootsTest {
    @Test
    fun cursorIncludesCompatibleCodexSkillsAfterNativeRoots() {
        val home = File("/test/home")
        val workspace = File("/test/workspace")
        val codexHome = File("/test/codex")

        assertEquals(
            listOf(
                File(workspace, ".cursor/skills"),
                File(workspace, ".agents/skills"),
                File(home, ".cursor/skills"),
                File(home, ".cursor/skills-cursor"),
                File(home, ".agents/skills"),
                File(codexHome, "skills"),
            ),
            skillRootsFor(AgentKind.Cursor, workspace, home, codexHome),
        )
    }
}
