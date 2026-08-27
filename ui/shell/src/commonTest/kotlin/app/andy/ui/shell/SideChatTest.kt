package app.andy.ui.shell

import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SideChatTest {
    private fun parent(
        agent: AgentKind = AgentKind.ClaudeCode,
        prompt: String = "Fix the dock tabs",
        latest: String? = "Also handle Chat",
        result: String? = "Browser is a singleton",
    ) = AgentTask(
        id = "parent-1",
        title = "Dock tabs",
        prompt = prompt,
        agent = agent,
        cwd = "/tmp/andy",
        originDir = "/tmp/andy",
        goal = "Ship the pane",
        latestPrompt = latest,
        completedResultText = result,
        createdAtMillis = 1,
    )

    @Test
    fun sideChatAgentPrefersAReadyContrastProvider() {
        val statuses = listOf(
            AgentCliStatus(AgentKind.ClaudeCode, binaryPath = "/bin/claude"),
            AgentCliStatus(AgentKind.Codex, binaryPath = "/bin/codex"),
        )
        assertEquals(AgentKind.Codex, sideChatAgent(AgentKind.ClaudeCode, statuses))
    }

    @Test
    fun sideChatAgentSkipsLocalBackendsWhenACloudProviderIsReady() {
        val statuses = listOf(
            AgentCliStatus(AgentKind.ClaudeCode, binaryPath = "/bin/claude"),
            AgentCliStatus(AgentKind.Ollama, binaryPath = "/bin/ollama"),
            AgentCliStatus(AgentKind.Codex, binaryPath = "/bin/codex"),
        )
        assertEquals(AgentKind.Codex, sideChatAgent(AgentKind.ClaudeCode, statuses))
    }

    @Test
    fun sideChatAgentFallsBackToParentWhenNothingElseIsReady() {
        val statuses = listOf(
            AgentCliStatus(AgentKind.ClaudeCode, binaryPath = "/bin/claude"),
        )
        assertEquals(AgentKind.ClaudeCode, sideChatAgent(AgentKind.ClaudeCode, statuses))
    }

    @Test
    fun sideChatDraftCopiesWorkspaceAndStaysReadOnly() {
        val draft = sideChatDraft(
            parent = parent(),
            question = "Is Chat a singleton?",
            statuses = listOf(
                AgentCliStatus(AgentKind.ClaudeCode, binaryPath = "/bin/claude"),
                AgentCliStatus(AgentKind.Codex, binaryPath = "/bin/codex"),
            ),
            providerDefaults = emptyMap(),
        )
        assertEquals(AgentKind.Codex, draft.agent)
        assertEquals("/tmp/andy", draft.directory)
        assertEquals(false, draft.useWorktree)
        assertEquals(AgentAutonomy.ReadOnly, draft.autonomy)
        assertEquals(AgentSandboxMode.ReadOnly, draft.sandboxMode)
        assertEquals("parent-1", draft.parentChatTaskId)
        assertTrue(draft.title.startsWith("Side ·"))
        assertTrue(draft.prompt.contains("Is Chat a singleton?"))
        assertTrue(draft.prompt.contains("Fix the dock tabs"))
        assertTrue(draft.prompt.contains("Also handle Chat"))
        assertTrue(draft.prompt.contains("Browser is a singleton"))
        assertTrue(draft.prompt.contains("Do NOT edit"))
    }

    @Test
    fun sideChatDraftUsesLaunchConfigWhenProvided() {
        val draft = sideChatDraft(
            parent = parent(),
            question = "Is Chat a singleton?",
            statuses = listOf(
                AgentCliStatus(AgentKind.ClaudeCode, binaryPath = "/bin/claude"),
                AgentCliStatus(AgentKind.Codex, binaryPath = "/bin/codex"),
            ),
            providerDefaults = emptyMap(),
            launch = SideChatLaunchConfig(
                agent = AgentKind.ClaudeCode,
                model = "opus",
                sandboxMode = AgentSandboxMode.WorkspaceWrite,
            ),
        )
        assertEquals(AgentKind.ClaudeCode, draft.agent)
        assertEquals("opus", draft.model)
        assertEquals(AgentSandboxMode.WorkspaceWrite, draft.sandboxMode)
        assertEquals(AgentAutonomy.Standard, draft.autonomy)
    }
}
