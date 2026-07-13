package app.andy.desktop.service.agents

import app.andy.model.AgentAutonomy
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.estimatedTokenCostUsd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun task(agent: AgentKind, sessionId: String? = null, autonomy: AgentAutonomy = AgentAutonomy.Standard) = AgentTask(
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

class ClaudeCodeAdapterTest {
    private val adapter = ClaudeCodeAdapter()

    @Test
    fun buildsHeadlessCommandWithSessionAndPermissionMode() {
        val argv = adapter.buildCommand("/bin/claude", task(AgentKind.ClaudeCode, sessionId = "sid-1"), mcpUrl = null)
        assertEquals("/bin/claude", argv.first())
        assertTrue("--session-id" in argv && "sid-1" in argv)
        assertTrue("acceptEdits" in argv)
        assertEquals("do the thing", argv.last())
    }

    @Test
    fun fullAutonomySkipsPermissions() {
        val argv = adapter.buildCommand("/bin/claude", task(AgentKind.ClaudeCode, autonomy = AgentAutonomy.Full), mcpUrl = null)
        assertTrue("--dangerously-skip-permissions" in argv)
        assertTrue("--permission-mode" !in argv)
    }

    @Test
    fun explicitPermissionModeOverridesAutonomy() {
        val argv = adapter.buildCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode).copy(sandboxMode = AgentSandboxMode.None),
            mcpUrl = null,
        )
        assertTrue("--dangerously-skip-permissions" in argv)
        assertTrue("--permission-mode" !in argv)
    }

    @Test
    fun sendsSelectedModelAndEffort() {
        val configured = task(AgentKind.ClaudeCode).copy(model = "opus", reasoningEffort = AgentReasoningEffort.Max)
        val argv = adapter.buildCommand("/bin/claude", configured, mcpUrl = null)
        assertTrue("--model" in argv && "opus" in argv)
        assertTrue("--effort" in argv && "max" in argv)
    }

    @Test
    fun planModeOverridesUnsafePermissionChoices() {
        val argv = adapter.buildCommand(
            "/bin/claude",
            task(AgentKind.ClaudeCode, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )

        assertTrue("--permission-mode" in argv && "plan" in argv)
        assertTrue("--dangerously-skip-permissions" !in argv)
    }

    @Test
    fun implementationHandoffUsesWritableFreshCommandWithoutPlanInstructions() {
        val argv = adapter.buildCommand("/bin/claude", implementationTask(AgentKind.ClaudeCode), mcpUrl = null)

        assertTrue("acceptEdits" in argv)
        assertTrue("plan" !in argv)
        assertTrue(!argv.last().contains("Plan mode is active"))
    }

    @Test
    fun tellsTextOnlyCliWhereAttachedImagesLive() {
        val withImage = task(AgentKind.ClaudeCode).copy(imagePaths = listOf("/tmp/mockup.png"))
        val argv = adapter.buildCommand("/bin/claude", withImage, mcpUrl = null)
        assertTrue(argv.last().contains("Attached image file"))
        assertTrue(argv.last().contains("/tmp/mockup.png"))
    }

    @Test
    fun parsesInitAssistantAndResult() {
        val init = adapter.parseLine("""{"type":"system","subtype":"init","session_id":"s-9","model":"claude-fable-5"}""", 1)
        assertEquals("s-9", (init.single() as AgentEvent.SessionStarted).sessionId)

        val assistant = adapter.parseLine(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"},{"type":"tool_use","name":"Bash","input":{"command":"git status"}}]}}""",
            2,
        )
        assertEquals(2, assistant.size)
        assertEquals("hello", (assistant[0] as AgentEvent.AssistantText).text)
        val tool = assistant[1] as AgentEvent.ToolCall
        assertEquals("Bash", tool.toolName)
        assertEquals("git status", tool.summary)

        val result = adapter.parseLine(
            """{"type":"result","subtype":"success","is_error":false,"result":"done","total_cost_usd":0.0421,"usage":{"input_tokens":10,"output_tokens":20},"duration_ms":1500}""",
            3,
        ).single() as AgentEvent.TaskResult
        assertTrue(result.success)
        assertEquals("done", result.finalText)
        assertEquals(0.0421, result.costUsd)
        assertEquals(10, result.inputTokens)
    }

    @Test
    fun parsesThinkingStreamDeltasAsThinking() {
        val delta = adapter.parseLine(
            """{"type":"thinking","subtype":"delta","text":"Checking the files...","session_id":"s-9"}""",
            4,
        ).single() as AgentEvent.Thinking

        assertEquals("Checking the files...", delta.text)
        assertTrue(delta.isStreamDelta)
    }

    @Test
    fun compactsRateLimitJsonIntoAToolStyleEvent() {
        val event = adapter.parseLine(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","rateLimitType":"five_hour","resetsAt":3600000,"overageDisabledReason":"org_level_disabled"}}""",
            0,
        ).single() as AgentEvent.ToolResult

        assertEquals("rate limit", event.toolName)
        assertTrue(event.summary.contains("Allowed"))
        assertTrue(event.summary.contains("five hour window"))
        assertTrue(!event.detail.contains("{"))
    }

    @Test
    fun unparseableLinesBecomeRaw() {
        val events = adapter.parseLine("plain text progress", 1)
        assertIs<AgentEvent.Raw>(events.single())
        assertTrue(adapter.parseLine("", 1).isEmpty())
    }

    @Test
    fun errorResultIsNotSuccess() {
        val result = adapter.parseLine("""{"type":"result","subtype":"error_during_execution","is_error":true}""", 1)
            .single() as AgentEvent.TaskResult
        assertTrue(!result.success)
    }
}

class CodexAdapterTest {
    private val adapter = CodexAdapter()

    @Test
    fun buildsExecCommandWithSandbox() {
        val argv = adapter.buildCommand("/bin/codex", task(AgentKind.Codex), mcpUrl = null)
        assertEquals(listOf("/bin/codex", "exec", "--json", "-C", "/tmp/repo", "--skip-git-repo-check", "--sandbox", "workspace-write", "do the thing"), argv)
    }

    @Test
    fun explicitNoSandboxOverridesAutonomy() {
        val configured = task(AgentKind.Codex).copy(sandboxMode = AgentSandboxMode.None)
        val argv = adapter.buildCommand("/bin/codex", configured, mcpUrl = null)

        assertTrue("--dangerously-bypass-approvals-and-sandbox" in argv)
        assertTrue("--sandbox" !in argv)
    }

    @Test
    fun noProjectContextDoesNotForceACurrentProjectDirectory() {
        val unscoped = task(AgentKind.Codex).copy(cwd = null, originDir = null)
        val argv = adapter.buildCommand("/bin/codex", unscoped, mcpUrl = null)
        assertTrue("-C" !in argv)
    }

    @Test
    fun resumeKeepsTheOriginalSandboxConfiguration() {
        val argv = adapter.buildResumeCommand("/bin/codex", task(AgentKind.Codex, sessionId = "thread-1"), "continue", emptyList(), mcpUrl = null)
            ?: error("Codex supports resume")

        assertTrue("--sandbox" !in argv)
        assertTrue("--dangerously-bypass-approvals-and-sandbox" !in argv)
    }

    @Test
    fun planModeKeepsPlanInstructionsOnFollowUps() {
        val argv = adapter.buildResumeCommand(
            "/bin/codex",
            task(AgentKind.Codex, sessionId = "thread-1").copy(planMode = true),
            "continue",
            emptyList(),
            mcpUrl = null,
        ) ?: error("Codex supports resume")

        assertTrue(argv.last().contains("Plan mode is active"))
    }

    @Test
    fun parsesThreadShape() {
        val started = adapter.parseLine("""{"type":"thread.started","thread_id":"t-42"}""", 1)
        assertEquals("t-42", (started.single() as AgentEvent.SessionStarted).sessionId)

        val message = adapter.parseLine("""{"type":"item.completed","item":{"type":"agent_message","text":"all set"}}""", 2)
        assertEquals("all set", (message.single() as AgentEvent.AssistantText).text)

        val command = adapter.parseLine("""{"type":"item.started","item":{"type":"command_execution","command":"ls -la"}}""", 3)
        assertEquals("ls -la", (command.single() as AgentEvent.ToolCall).summary)

        val done = adapter.parseLine("""{"type":"turn.completed","usage":{"input_tokens":5,"output_tokens":9}}""", 4)
            .single() as AgentEvent.TaskResult
        assertTrue(done.success)
        assertEquals(9, done.outputTokens)
    }

    @Test
    fun parsesContextWindowUsage() {
        val context = adapter.parseLine(
            """{"type":"token_count","info":{"total_token_usage":120000,"model_context_window":272000}}""",
            5,
        ).single() as AgentEvent.ContextUsage

        assertEquals(120000, context.usedTokens)
        assertEquals(272000, context.windowTokens)
    }

    @Test
    fun estimatesCostWhenCodexReportsTokensButNotABilledTotal() {
        val estimate = task(AgentKind.Codex).copy(model = "gpt-5.6-terra").estimatedTokenCostUsd(1_000_000, 1_000_000)
        assertEquals(11.25, estimate)
    }

    @Test
    fun parsesLegacyShape() {
        val message = adapter.parseLine("""{"id":"1","msg":{"type":"agent_message","message":"legacy hello"}}""", 1)
        assertEquals("legacy hello", (message.single() as AgentEvent.AssistantText).text)

        val complete = adapter.parseLine("""{"id":"2","msg":{"type":"task_complete","last_agent_message":"bye"}}""", 2)
            .single() as AgentEvent.TaskResult
        assertEquals("bye", complete.finalText)
    }

    @Test
    fun hidesKnownNonFatalCodexStartupDiagnostics() {
        assertTrue(adapter.parseLine("Reading additional input from stdin...", 1).isEmpty())
        assertTrue(
            adapter.parseLine(
                "2026-07-10T12:12:47Z ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, when AuthRequired(AuthRequiredError { resource_metadata: \"https://api.githubcopilot.com/.well-known/oauth-protected-resource/mcp/\" })",
                1,
            ).isEmpty(),
        )
    }

    @Test
    fun resumeUsesThreadId() {
        val argv = adapter.buildResumeCommand("/bin/codex", task(AgentKind.Codex, sessionId = "t-42"), "continue", imagePaths = emptyList(), mcpUrl = null)
        assertEquals(listOf("/bin/codex", "exec", "resume", "--json", "--skip-git-repo-check", "t-42", "continue"), argv)
    }

    @Test
    fun resumePreservesAndyMcpConfiguration() {
        val argv = adapter.buildResumeCommand(
            "/bin/codex",
            task(AgentKind.Codex, sessionId = "t-42"),
            "continue",
            imagePaths = emptyList(),
            mcpUrl = "http://127.0.0.1:8565/mcp",
        ).orEmpty()

        assertTrue("mcp_servers.andy.url=\"http://127.0.0.1:8565/mcp\"" in argv)
    }

    @Test
    fun sendsSelectedModelAndReasoningEffort() {
        val configured = task(AgentKind.Codex).copy(model = "gpt-5.6-terra", reasoningEffort = AgentReasoningEffort.High)
        val argv = adapter.buildCommand("/bin/codex", configured, mcpUrl = null)
        assertTrue("--model" in argv && "gpt-5.6-terra" in argv)
        assertTrue("model_reasoning_effort=\"high\"" in argv)
    }

    @Test
    fun planModeUsesReadOnlySandboxAndPlanInstructions() {
        val argv = adapter.buildCommand(
            "/bin/codex",
            task(AgentKind.Codex, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )

        assertTrue("--sandbox" in argv && "read-only" in argv)
        assertTrue(argv.last().contains("Plan mode is active"))
    }

    @Test
    fun implementationHandoffStartsNewWritableExecInsteadOfResume() {
        val argv = adapter.buildCommand("/bin/codex", implementationTask(AgentKind.Codex), mcpUrl = null)

        assertEquals("exec", argv[1])
        assertTrue("resume" !in argv)
        assertTrue("--sandbox" in argv && "workspace-write" in argv)
        assertTrue(!argv.last().contains("Plan mode is active"))
    }

    @Test
    fun sendsAttachedImagesWithTheNativeCodexFlag() {
        val withImage = task(AgentKind.Codex).copy(imagePaths = listOf("/tmp/mockup.png"))
        val argv = adapter.buildCommand("/bin/codex", withImage, mcpUrl = null)
        assertTrue("--image" in argv && "/tmp/mockup.png" in argv)
    }

    @Test
    fun sendsFollowUpImagesWithTheNativeCodexFlag() {
        val argv = adapter.buildResumeCommand(
            "/bin/codex",
            task(AgentKind.Codex, sessionId = "t-42"),
            "review this",
            imagePaths = listOf("/tmp/follow-up.png"),
            mcpUrl = null,
        ).orEmpty()
        assertTrue("--image" in argv && "/tmp/follow-up.png" in argv)
    }
}

class AntigravityAdapterTest {
    private val adapter = AntigravityAdapter()

    @Test
    fun everyLineIsRaw() {
        assertIs<AgentEvent.ToolResult>(adapter.parseLine("""{"type":"assistant"}""", 1).single())
        assertIs<AgentEvent.Raw>(adapter.parseLine("thinking about it", 1).single())
    }

    @Test
    fun autonomyMapsToModeFlags() {
        val standard = adapter.buildCommand("/bin/agy", task(AgentKind.Antigravity), mcpUrl = null)
        assertTrue("--mode" in standard && "accept-edits" in standard)
        val full = adapter.buildCommand("/bin/agy", task(AgentKind.Antigravity, autonomy = AgentAutonomy.Full), mcpUrl = null)
        assertTrue("--dangerously-skip-permissions" in full)
        val readOnly = adapter.buildCommand("/bin/agy", task(AgentKind.Antigravity, autonomy = AgentAutonomy.ReadOnly), mcpUrl = null)
        assertTrue("plan" in readOnly && "--sandbox" in readOnly)
    }

    @Test
    fun explicitPermissionModeOverridesAutonomy() {
        val argv = adapter.buildCommand(
            "/bin/agy",
            task(AgentKind.Antigravity).copy(sandboxMode = AgentSandboxMode.None),
            mcpUrl = null,
        )
        assertTrue("--dangerously-skip-permissions" in argv)
    }

    @Test
    fun selectedModelAndLevelBecomeAntigravityVariant() {
        val configured = task(AgentKind.Antigravity).copy(model = "Gemini 3.5 Flash", reasoningEffort = AgentReasoningEffort.High)
        val argv = adapter.buildCommand("/bin/agy", configured, mcpUrl = null)
        assertTrue("--model" in argv && "Gemini 3.5 Flash (High)" in argv)
    }

    @Test
    fun planModeOverridesUnsafePermissionChoices() {
        val argv = adapter.buildCommand(
            "/bin/agy",
            task(AgentKind.Antigravity, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )

        assertTrue("--mode" in argv && "plan" in argv)
        assertTrue("--sandbox" in argv)
        assertTrue("--dangerously-skip-permissions" !in argv)
    }

    @Test
    fun implementationHandoffUsesAcceptEditsWithoutPlanInstructions() {
        val argv = adapter.buildCommand("/bin/agy", implementationTask(AgentKind.Antigravity), mcpUrl = null)

        assertTrue("accept-edits" in argv)
        assertTrue("plan" !in argv)
        assertTrue(!argv.any { it.contains("Plan mode is active") })
    }
}

class CursorAdapterTest {
    private val adapter = CursorAdapter()

    @Test
    fun sendsTheSelectedFastReasoningVariant() {
        val configured = task(AgentKind.Cursor).copy(
            model = "cursor-grok-4.5",
            reasoningEffort = AgentReasoningEffort.High,
            fastMode = true,
        )
        val argv = adapter.buildCommand("/bin/cursor-agent", configured, mcpUrl = null)
        assertTrue("--model" in argv && "cursor-grok-4.5-high-fast" in argv)
    }

    @Test
    fun migratesLegacyCursorDisplayNameToCliSlug() {
        val configured = task(AgentKind.Cursor).copy(
            model = "Grok 4.5",
            reasoningEffort = AgentReasoningEffort.Medium,
            fastMode = false,
        )
        val argv = adapter.buildCommand("/bin/cursor-agent", configured, mcpUrl = null)
        assertTrue("--model" in argv && "cursor-grok-4.5-medium" in argv)
    }

    @Test
    fun defaultsGrokEffortWhenUnset() {
        val configured = task(AgentKind.Cursor).copy(
            model = "cursor-grok-4.5",
            reasoningEffort = null,
            fastMode = false,
        )
        val argv = adapter.buildCommand("/bin/cursor-agent", configured, mcpUrl = null)
        assertTrue("--model" in argv && "cursor-grok-4.5-high" in argv)
        assertTrue("cursor-grok-4.5" !in argv.filter { it.startsWith("cursor-grok") })
    }

    @Test
    fun composerWithoutEffortDoesNotAppendSuffix() {
        val configured = task(AgentKind.Cursor).copy(
            model = "composer-2.5",
            reasoningEffort = null,
            fastMode = true,
        )
        val argv = adapter.buildCommand("/bin/cursor-agent", configured, mcpUrl = null)
        assertTrue("--model" in argv && "composer-2.5-fast" in argv)
    }

    @Test
    fun explicitSandboxModeUsesCursorSandboxFlags() {
        val disabled = adapter.buildCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor).copy(sandboxMode = AgentSandboxMode.None),
            mcpUrl = null,
        )
        assertTrue("--sandbox" in disabled && "disabled" in disabled)

        val readOnly = adapter.buildCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor).copy(sandboxMode = AgentSandboxMode.ReadOnly),
            mcpUrl = null,
        )
        assertTrue("--mode" in readOnly && "plan" in readOnly)
        assertTrue("--sandbox" in readOnly && "enabled" in readOnly)
    }

    @Test
    fun planModeOverridesFullAutonomy() {
        val argv = adapter.buildCommand(
            "/bin/cursor-agent",
            task(AgentKind.Cursor, autonomy = AgentAutonomy.Full).copy(planMode = true),
            mcpUrl = null,
        )

        assertTrue("--mode" in argv && "plan" in argv)
        assertTrue("--sandbox" in argv && "enabled" in argv)
        assertTrue("--force" !in argv)
    }

    @Test
    fun implementationHandoffUsesWritableSandboxWithoutPlanInstructions() {
        val argv = adapter.buildCommand("/bin/cursor-agent", implementationTask(AgentKind.Cursor), mcpUrl = null)

        assertTrue("--sandbox" in argv && "enabled" in argv)
        assertTrue("plan" !in argv)
        assertTrue(!argv.last().contains("Plan mode is active"))
    }

    @Test
    fun parsesNestedToolCallStartedEvents() {
        val events = adapter.parseLine(
            """
            {"type":"tool_call","subtype":"started","call_id":"toolu_01","tool_call":{"readToolCall":{"args":{"path":"/tmp/test.txt"}}},"session_id":"s-1"}
            """.trimIndent(),
            1,
        )
        val tool = events.single() as AgentEvent.ToolCall
        assertEquals("Read", tool.toolName)
        assertEquals("/tmp/test.txt", tool.summary)
        assertEquals("/tmp/test.txt", tool.detail)
    }

    @Test
    fun parsesNestedToolCallCompletedEvents() {
        val events = adapter.parseLine(
            """
            {"type":"tool_call","subtype":"completed","call_id":"toolu_01","tool_call":{"shellToolCall":{"args":{"command":"ls -la"},"result":{"success":{"stdout":"total 0","stderr":"","exitCode":0}}}},"session_id":"s-1"}
            """.trimIndent(),
            2,
        )
        val result = events.single() as AgentEvent.ToolResult
        assertEquals("Shell", result.toolName)
        assertEquals("total 0", result.summary)
        assertEquals("total 0", result.detail)
        assertTrue(!result.isError)
    }
}
