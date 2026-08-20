package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentThreadImportTest {
    @Test
    fun importableProvidersExcludeLocalBackendsAndAntigravity() {
        assertTrue(AgentKind.Codex.canImportVendorThread)
        assertTrue(AgentKind.ClaudeCode.canImportVendorThread)
        assertTrue(AgentKind.Cursor.canImportVendorThread)
        assertTrue(AgentKind.OpenCode.canImportVendorThread)
        assertFalse(AgentKind.Ollama.canImportVendorThread)
        assertFalse(AgentKind.LMStudio.canImportVendorThread)
        assertFalse(AgentKind.Antigravity.canImportVendorThread)
        assertEquals(8, ImportableAgentKinds.size)
    }

    @Test
    fun copyMentionsTheSelectedProvider() {
        assertEquals("Claude", AgentKind.ClaudeCode.importTileLabel)
        assertEquals("Paste a Codex thread id.", AgentKind.Codex.importIdPlaceholder())
        assertTrue(AgentKind.Codex.importIdHelper().contains("thread id"))
        assertTrue(AgentKind.Goose.importIdHelper().contains("YYYYMMDD_N"))
    }

    @Test
    fun importedDraftResumesOnTheTerminalLane() {
        val draft = AgentTaskDraft(
            title = "",
            prompt = "",
            agent = AgentKind.Codex,
            projectId = null,
        ).withImportedVendorSession("  thread-123  ")
        assertEquals("thread-123", draft.vendorSessionId)
        assertEquals(AgentLaneKind.Terminal, draft.lane)
        assertEquals("Imported Codex thread", draft.title)
        assertEquals("Imported Codex thread", draft.fallbackTitle())
        assertFalse(draft.openClawNewSession)
        assertFalse(draft.useWorktree)
    }

    @Test
    fun cursorHelperMentionsWorkspaceFolder() {
        assertTrue(AgentKind.Cursor.importIdHelper().contains("workspace folder"))
    }
}
