package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.followUpCliPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun task(
    agent: AgentKind,
    sessionId: String? = null,
    autonomy: AgentAutonomy = AgentAutonomy.Standard,
) = AgentTask(
    id = "task-abc123",
    title = "test",
    prompt = "do the thing",
    agent = agent,
    cwd = "/tmp/repo",
    originDir = "/tmp/repo",
    autonomy = autonomy,
    vendorSessionId = sessionId,
    createdAtMillis = 0,
)

private fun implementationTask(agent: AgentKind): AgentTask = task(agent, autonomy = AgentAutonomy.Full).copy(
    planMode = false,
    sandboxMode = AgentSandboxMode.WorkspaceWrite,
    implementationPrompt = "Begin implementation. Implement the completed plan.",
)

class ClaudeCodeInteractiveAdapterTest {
    private val adapter = ClaudeCodeAdapter()

    @Test
    fun interactiveCommandHasNoHeadlessFlagsAndLeavesPromptForPty() {
        val argv = adapter.buildInteractiveCommand("/bin/claude", task(AgentKind.ClaudeCode), mcpUrl = null)
        assertEquals("/bin/claude", argv.first())
        assertTrue("-p" !in argv)
        assertTrue("stream-json" !in argv)
        assertTrue("acceptEdits" in argv)
        assertEquals("do the thing", argv.last())
        assertTrue(adapter.embedsInitialPrompt)
    }

    @Test
    fun fullAutonomySkipsPermissions() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode, autonomy = AgentAutonomy.Full),
            mcpUrl = null,
        )
        assertTrue("--dangerously-skip-permissions" in argv)
        assertTrue("--permission-mode" !in argv)
    }

    @Test
    fun scratchWorkspaceSkipsPermissionsForFullAutonomy() {
        val scratch = AgentScratchWorkspace.path().absolutePath
        val argv = adapter.buildInteractiveCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode, autonomy = AgentAutonomy.Full).copy(cwd = scratch),
            mcpUrl = null,
        )
        assertTrue("--dangerously-skip-permissions" in argv)
        assertTrue("--permission-mode" !in argv)
    }

    @Test
    fun scratchWorkspaceSkipsPermissionsForExplicitBypassSandbox() {
        val scratch = AgentScratchWorkspace.path().absolutePath
        val argv = adapter.buildInteractiveCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode).copy(cwd = scratch, sandboxMode = AgentSandboxMode.None),
            mcpUrl = null,
        )
        assertTrue("--dangerously-skip-permissions" in argv)
        assertTrue("--permission-mode" !in argv)
    }

    @Test
    fun explicitSandboxModeOverridesAutonomy() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode).copy(sandboxMode = AgentSandboxMode.None),
            mcpUrl = null,
        )
        assertTrue("--dangerously-skip-permissions" in argv)
    }

    @Test
    fun sendsSelectedModelEffortBudgetAndMcp() {
        val configured = task(AgentKind.ClaudeCode).copy(
            model = "opus",
            reasoningEffort = AgentReasoningEffort.Max,
            maxBudgetUsd = 12.5,
        )
        val argv = adapter.buildInteractiveCommand("/bin/claude", configured, mcpUrl = "http://127.0.0.1:8565/mcp")
        assertTrue("--model" in argv && "opus" in argv)
        assertTrue("--effort" in argv && "max" in argv)
        assertTrue("--max-budget-usd" in argv && "12.5" in argv)
        assertTrue("--mcp-config" in argv)
        assertTrue(argv.any { it.contains("http://127.0.0.1:8565/mcp") })
    }

    @Test
    fun planModeOverridesUnsafePermissionChoices() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )
        assertTrue("--permission-mode" in argv && "plan" in argv)
        assertTrue("--dangerously-skip-permissions" !in argv)
    }

    @Test
    fun implementationHandoffUsesWritableFreshCommand() {
        val argv = adapter.buildInteractiveCommand("/bin/claude", implementationTask(AgentKind.ClaudeCode), mcpUrl = null)
        assertTrue("acceptEdits" in argv)
        assertTrue("plan" !in argv)
        assertTrue(argv.none { it.contains("Plan mode is active") })
    }

    @Test
    fun attachedImagesAreNotForcedIntoArgv() {
        val withImage = task(AgentKind.ClaudeCode).copy(imagePaths = listOf("/tmp/mockup.png"))
        val argv = adapter.buildInteractiveCommand("/bin/claude", withImage, mcpUrl = null)
        // Image paths are described inside the prompt text (text-only CLIs).
        assertTrue(argv.last().contains("/tmp/mockup.png"))
        assertTrue(argv.last().contains("Attached image file"))
    }

    @Test
    fun resumeIncludesAttachedImagesInPrompt() {
        val baseTask = task(AgentKind.ClaudeCode, sessionId = "sid-1")
        val followUp = baseTask.followUpCliPayload("continue", listOf("/tmp/screenshot.png"), emptyList()).prompt
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/claude",
            baseTask,
            mcpUrl = null,
            followUp = followUp,
        )
        assertNotNull(argv)
        assertTrue(argv!!.last().contains("/tmp/screenshot.png"))
        assertTrue(argv.last().contains("Attached image file"))
    }

    @Test
    fun resumeUsesSessionIdFlag() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode, sessionId = "sid-1"),
            mcpUrl = null,
            followUp = "continue",
        )
        assertNotNull(argv)
        assertTrue("--resume" in argv!! && "sid-1" in argv)
        assertTrue("-p" !in argv)
        assertEquals("continue", argv.last())
    }

    @Test
    fun resumeWithoutSessionReseedsOriginalPrompt() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode).copy(prompt = "original ask"),
            mcpUrl = null,
            followUp = "hello again",
        )
        assertNotNull(argv)
        assertTrue("--resume" !in argv!!)
        val embedded = argv.last()
        assertTrue(embedded.contains("original ask"), embedded)
        assertTrue(embedded.contains("hello again"), embedded)
    }

    @Test
    fun interactiveResumeShellOneLinerQuotesSession() {
        val quoted = adapter.interactiveResumeCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode, sessionId = "sid-1"),
        )
        assertTrue(quoted.contains("--resume"))
        assertTrue(quoted.contains("sid-1"))
    }
}

class CodexInteractiveAdapterTest {
    private val adapter = CodexAdapter()

    @Test
    fun interactiveCommandIsNotExecJson() {
        val argv = adapter.buildInteractiveCommand("/bin/codex", task(AgentKind.Codex), mcpUrl = null)
        assertEquals("/bin/codex", argv.first())
        assertTrue("exec" !in argv)
        assertTrue("--json" !in argv)
        assertTrue("-C" in argv && "/tmp/repo" in argv)
        assertTrue("--sandbox" in argv && "workspace-write" in argv)
        assertEquals("do the thing", argv.last())
    }

    @Test
    fun explicitNoSandboxOverridesAutonomy() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/codex",
            task(AgentKind.Codex).copy(sandboxMode = AgentSandboxMode.None),
            mcpUrl = null,
        )
        assertTrue("--dangerously-bypass-approvals-and-sandbox" in argv)
        assertTrue("--sandbox" !in argv)
    }

    @Test
    fun sendsAttachedImagesWithNativeFlag() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/codex",
            task(AgentKind.Codex).copy(imagePaths = listOf("/tmp/mockup.png")),
            mcpUrl = null,
        )
        assertTrue("--image" in argv && "/tmp/mockup.png" in argv)
    }

    @Test
    fun planModeUsesReadOnlySandbox() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/codex",
            task(AgentKind.Codex, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )
        assertTrue("--sandbox" in argv && "read-only" in argv)
        assertTrue(argv.last().contains("Plan mode is active"))
    }

    @Test
    fun resumeSendsAttachedImagesWithNativeFlag() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/codex",
            task(AgentKind.Codex, sessionId = "t-42"),
            mcpUrl = null,
            followUp = "see attached",
            followUpImagePaths = listOf("/tmp/mockup.png"),
        )
        assertNotNull(argv)
        assertTrue("--image" in argv!! && "/tmp/mockup.png" in argv)
    }

    @Test
    fun resumeUsesThreadSubcommand() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/codex",
            task(AgentKind.Codex, sessionId = "t-42"),
            mcpUrl = null,
        )
        assertNotNull(argv)
        assertEquals(listOf("/bin/codex", "resume", "t-42"), argv.take(3))
        assertTrue("exec" !in argv)
    }

    @Test
    fun resumePreservesMcpConfiguration() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/codex",
            task(AgentKind.Codex, sessionId = "t-42"),
            mcpUrl = "http://127.0.0.1:8565/mcp",
        ).orEmpty()
        assertTrue("mcp_servers.andy.url=\"http://127.0.0.1:8565/mcp\"" in argv)
    }
}

class CursorInteractiveAdapterTest {
    private val adapter = CursorAdapter()

    @Test
    fun interactiveCommandHasNoHeadlessFlags() {
        val argv = adapter.buildInteractiveCommand("/bin/cursor-agent", task(AgentKind.Cursor), mcpUrl = null)
        assertEquals("/bin/cursor-agent", argv.first())
        assertTrue("-p" !in argv)
        assertTrue("stream-json" !in argv)
        assertEquals("do the thing", argv.last())
    }

    @Test
    fun planModeUsesPlanSandboxFlags() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )
        assertTrue("--mode" in argv && "plan" in argv)
        assertTrue("--sandbox" in argv && "enabled" in argv)
        assertTrue("--force" !in argv)
    }

    @Test
    fun fullAutonomyAddsForceWhenNotPlanning() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor, autonomy = AgentAutonomy.Full),
            mcpUrl = null,
        )
        assertTrue("--force" in argv)
    }

    @Test
    fun resumeIncludesAttachedImagesInPrompt() {
        val baseTask = task(AgentKind.Cursor, sessionId = "chat-9")
        val followUp = baseTask.followUpCliPayload("hello again", listOf("/tmp/ui.png"), emptyList()).prompt
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/cursor-agent",
            baseTask,
            mcpUrl = null,
            followUp = followUp,
        )
        assertNotNull(argv)
        assertTrue(argv!!.last().contains("/tmp/ui.png"))
        assertTrue(argv.last().contains("Attached image file"))
    }

    @Test
    fun resumeUsesChatId() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor, sessionId = "chat-9"),
            mcpUrl = null,
            followUp = "hello again",
        )
        assertNotNull(argv)
        assertTrue("--resume" in argv!! && "chat-9" in argv)
        assertEquals("hello again", argv.last())
    }

    @Test
    fun freshInteractiveCommandResumesAllocatedChatId() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor, sessionId = "chat-new"),
            mcpUrl = null,
        )
        assertTrue("--resume" in argv && "chat-new" in argv)
        assertEquals("do the thing", argv.last())
    }

    @Test
    fun resumeWithoutChatIdReseedsOriginalPrompt() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor).copy(prompt = "what is the current timestamp"),
            mcpUrl = null,
            followUp = "hello again",
        )
        assertNotNull(argv)
        assertTrue("--resume" !in argv!!)
        val embedded = argv.last()
        assertTrue(embedded.contains("what is the current timestamp"), embedded)
        assertTrue(embedded.contains("hello again"), embedded)
    }
}

class AntigravityInteractiveAdapterTest {
    private val adapter = AntigravityAdapter()

    @Test
    fun interactiveCommandHasNoPrintFlag() {
        val argv = adapter.buildInteractiveCommand("/bin/agy", task(AgentKind.Antigravity), mcpUrl = null)
        assertEquals("/bin/agy", argv.first())
        assertTrue("-p" !in argv)
        assertTrue("--print" !in argv)
        assertTrue("--mode" in argv && "accept-edits" in argv)
        assertTrue("--prompt-interactive" in argv)
        assertEquals("do the thing", argv.last())
        assertTrue(adapter.embedsInitialPrompt)
    }

    @Test
    fun modelFlagIncludesRequiredEffort() {
        val argv = adapter.buildInteractiveCommand(
            "/bin/agy",
            task(AgentKind.Antigravity).copy(model = "gemini-3.6-flash"),
            mcpUrl = null,
        )
        assertTrue("--model" in argv && "gemini-3.6-flash" in argv)
        assertTrue("--effort" in argv && "high" in argv)
    }

    @Test
    fun resumeIncludesAttachedImagesInPrompt() {
        val baseTask = task(AgentKind.Antigravity).copy(prompt = "original ask")
        val followUp = baseTask.followUpCliPayload("what time is it", listOf("/tmp/mockup.png"), emptyList()).prompt
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/agy",
            baseTask,
            mcpUrl = null,
            followUp = followUp,
        )
        assertNotNull(argv)
        val embedded = argv!![argv.indexOf("--prompt-interactive") + 1]
        assertTrue(embedded.contains("/tmp/mockup.png"), embedded)
        assertTrue(embedded.contains("Attached image file"), embedded)
    }

    @Test
    fun resumeUsesConversationIdWhenPresent() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/agy",
            task(AgentKind.Antigravity).copy(vendorSessionId = "conv-123", prompt = "original ask"),
            mcpUrl = null,
            followUp = "what time is it",
        )
        // Stored id is untrusted without a transcript/history match for "original ask".
        assertNotNull(argv)
        assertTrue("--continue" !in argv!!)
        assertTrue("--prompt-interactive" in argv)
        val promptIdx = argv.indexOf("--prompt-interactive")
        val embedded = argv[promptIdx + 1]
        assertTrue(embedded.contains("original ask"), embedded)
        assertTrue(embedded.contains("what time is it"), embedded)
        assertTrue("--conversation" !in argv)
    }

    @Test
    fun resumeWithoutConversationIdReseedsOriginalPrompt() {
        val argv = adapter.buildInteractiveResumeCommand(
            "/bin/agy",
            task(AgentKind.Antigravity).copy(prompt = "what is the current timestamp"),
            mcpUrl = null,
            followUp = "hello",
        )
        assertNotNull(argv)
        assertTrue("--continue" !in argv!!)
        assertTrue("--conversation" !in argv)
        assertTrue("--prompt-interactive" in argv)
        val embedded = argv[argv.indexOf("--prompt-interactive") + 1]
        assertTrue(embedded.contains("what is the current timestamp"), embedded)
        assertTrue(embedded.contains("hello"), embedded)
    }

    @Test
    fun resumePromptHelperKeepsFollowUpOnlyWhenBound() {
        val task = task(AgentKind.Antigravity).copy(prompt = "original")
        assertEquals(
            "follow",
            AntigravityAdapter.resumePrompt(task, "follow", boundToConversation = true),
        )
        val unbound = AntigravityAdapter.resumePrompt(task, "follow", boundToConversation = false)
        assertNotNull(unbound)
        assertTrue(unbound!!.contains("original"))
        assertTrue(unbound.contains("follow"))
    }

    @Test
    fun interactiveResumeShellOneLinerOmitsUntrustedConversation() {
        val quoted = adapter.interactiveResumeCommand(
            "/bin/agy",
            task(AgentKind.Antigravity).copy(vendorSessionId = "conv-9", prompt = "never sent"),
        )
        // Unverified stored ids must not appear in the escape-hatch one-liner.
        assertTrue("--conversation" !in quoted)
        assertTrue("--continue" !in quoted)
    }
}
