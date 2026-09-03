package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillRootsTest {
    @Test
    fun hermesAndOpenClawUseNativeRootsBeforeWorkspaceFallbacks() {
        val home = File("/test/home")
        val workspace = File("/test/workspace")
        assertEquals(listOf(File(workspace, ".hermes/skills"), File(home, ".hermes/skills")), skillRootsFor(AgentKind.Hermes, workspace, home))
        assertEquals(
            listOf(File(workspace, ".openclaw/skills"), File(workspace, "skills"), File(home, ".openclaw/skills")),
            skillRootsFor(AgentKind.OpenClaw, workspace, home),
        )
    }
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

    @Test
    fun providersDoNotShareClaudeAndOpenCodeWorkspaceRoots() {
        val home = File("/test/home")
        val workspace = File("/test/workspace")
        assertEquals(
            listOf(
                File(workspace, ".opencode/skills"),
                File(home, ".config/opencode/skills"),
                File(home, ".opencode/skills"),
            ),
            skillRootsFor(AgentKind.OpenCode, workspace, home),
        )
        assertEquals(
            listOf(
                File(home, ".claude/skills"),
                File(workspace, ".claude/skills"),
                File(home, ".agents/skills"),
            ),
            skillRootsFor(AgentKind.ClaudeCode, workspace, home),
        )
    }

    @Test
    fun gooseUsesNativeAndPortableSkillRoots() {
        val home = File("/test/home")
        val workspace = File("/test/workspace")
        assertEquals(
            listOf(
                File(workspace, ".goose/skills"),
                File(workspace, ".agents/skills"),
                File(home, ".config/goose/skills"),
                File(home, ".goose/skills"),
                File(home, ".agents/skills"),
            ),
            skillRootsFor(AgentKind.Goose, workspace, home),
        )
    }

    @Test
    fun piIncludesProjectAndGlobalRoots() {
        val home = File("/test/home")
        val workspace = File("/test/workspace")
        assertEquals(
            listOf(
                File(workspace, ".pi/skills"),
                File(workspace, ".agents/skills"),
                File(home, ".pi/agent/skills"),
                File(home, ".agents/skills"),
            ),
            skillRootsFor(AgentKind.Pi, workspace, home),
        )
    }

    @Test
    fun antigravityIncludesPortableAgentsSkillsAfterNativeRoots() {
        val home = File("/test/home")
        val workspace = File("/test/workspace")
        assertEquals(
            listOf(
                File(workspace, ".agents/skills"),
                File(home, ".gemini/antigravity-cli/skills"),
                File(home, ".agents/skills"),
            ),
            skillRootsFor(AgentKind.Antigravity, workspace, home),
        )
    }
}
