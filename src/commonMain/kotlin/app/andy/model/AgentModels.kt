package app.andy.model

enum class AgentKind(val label: String, val cliName: String) {
    ClaudeCode("Claude Code", "claude"),
    Codex("Codex", "codex"),
    Cursor("Cursor", "cursor-agent"),
    Antigravity("Antigravity", "agy"),
    OpenCode("OpenCode", "opencode"),
    Pi("Pi", "pi"),
    Hermes("Hermes", "hermes"),
    OpenClaw("OpenClaw", "openclaw"),
}

/** The transport that owns a task's provider conversation. Persisted per task. */
enum class AgentLaneKind {
    Terminal,
    Acp,
}

/** Providers with a first-party or registry ACP adapter. */
val AgentKind.acpSupported: Boolean
    get() = when (this) {
        AgentKind.ClaudeCode,
        AgentKind.Codex,
        AgentKind.Cursor,
        AgentKind.OpenCode,
        AgentKind.Pi,
        -> true
        AgentKind.Antigravity, AgentKind.Hermes, AgentKind.OpenClaw -> false
    }

/** The default lane for newly-created tasks; persisted tasks never re-derive this value. */
fun AgentKind.defaultLane(): AgentLaneKind =
    if (acpSupported) AgentLaneKind.Acp else AgentLaneKind.Terminal

/** Unified autonomy dial; each adapter maps it to vendor-specific flags. */
enum class AgentAutonomy(val label: String) {
    ReadOnly("read-only"),
    Standard("standard"),
    Full("full"),
}

/**
 * A provider-specific execution safety choice, kept separate from the cross-provider autonomy dial.
 * Each adapter maps the value to the most precise sandbox or permission control its CLI offers.
 */
enum class AgentSandboxMode(val label: String) {
    ReadOnly("read-only"),
    WorkspaceWrite("workspace write"),
    None("no sandbox"),
}

fun AgentAutonomy.defaultSandboxMode(): AgentSandboxMode = when (this) {
    AgentAutonomy.ReadOnly -> AgentSandboxMode.ReadOnly
    AgentAutonomy.Standard -> AgentSandboxMode.WorkspaceWrite
    AgentAutonomy.Full -> AgentSandboxMode.None
}

fun AgentKind.sandboxControlLabel(): String = when (this) {
    AgentKind.Codex, AgentKind.Cursor -> "sandbox"
    AgentKind.ClaudeCode, AgentKind.Antigravity, AgentKind.OpenCode, AgentKind.Pi,
    AgentKind.Hermes, AgentKind.OpenClaw -> "approvals"
}

fun AgentSandboxMode.labelFor(agent: AgentKind): String = when (agent) {
    AgentKind.Codex -> label
    AgentKind.ClaudeCode -> when (this) {
        AgentSandboxMode.ReadOnly -> "plan"
        AgentSandboxMode.WorkspaceWrite -> "accept edits"
        AgentSandboxMode.None -> "skip permissions"
    }
    AgentKind.Cursor -> when (this) {
        AgentSandboxMode.ReadOnly -> "plan + sandbox"
        AgentSandboxMode.WorkspaceWrite -> "sandbox enabled"
        AgentSandboxMode.None -> "sandbox disabled"
    }
    AgentKind.Antigravity -> when (this) {
        AgentSandboxMode.ReadOnly -> "plan + sandbox"
        AgentSandboxMode.WorkspaceWrite -> "accept edits"
        AgentSandboxMode.None -> "skip permissions"
    }
    AgentKind.OpenCode -> when (this) {
        AgentSandboxMode.ReadOnly -> "plan"
        AgentSandboxMode.WorkspaceWrite -> "ask on tools"
        AgentSandboxMode.None -> "auto-approve"
    }
    AgentKind.Pi -> when (this) {
        AgentSandboxMode.ReadOnly -> "read-only prompt"
        AgentSandboxMode.WorkspaceWrite -> "standard"
        AgentSandboxMode.None -> "unrestricted"
    }
    AgentKind.Hermes -> when (this) {
        AgentSandboxMode.ReadOnly -> "read-only prompt"
        AgentSandboxMode.WorkspaceWrite -> "standard"
        AgentSandboxMode.None -> "yolo"
    }
    AgentKind.OpenClaw -> when (this) {
        AgentSandboxMode.ReadOnly -> "approval prompt"
        AgentSandboxMode.WorkspaceWrite -> "ask on tools"
        AgentSandboxMode.None -> "auto-approve where allowed"
    }
}

fun AgentSandboxMode.descriptionFor(agent: AgentKind): String = when (agent) {
    AgentKind.Codex -> when (this) {
        AgentSandboxMode.ReadOnly -> "Codex can inspect files but cannot modify the workspace."
        AgentSandboxMode.WorkspaceWrite -> "Codex can edit the workspace, but network access remains sandboxed."
        AgentSandboxMode.None -> "No sandbox: Codex can use your host permissions, including network tools such as GitHub."
    }
    AgentKind.ClaudeCode -> when (this) {
        AgentSandboxMode.ReadOnly -> "Claude Code runs in plan mode."
        AgentSandboxMode.WorkspaceWrite -> "Claude Code accepts edits while retaining permission checks."
        AgentSandboxMode.None -> "Claude Code has no filesystem sandbox flag; this skips its permission checks."
    }
    AgentKind.Cursor -> when (this) {
        AgentSandboxMode.ReadOnly -> "Cursor runs in plan mode with its sandbox enabled."
        AgentSandboxMode.WorkspaceWrite -> "Cursor's CLI sandbox is explicitly enabled."
        AgentSandboxMode.None -> "Cursor's CLI sandbox is explicitly disabled."
    }
    AgentKind.Antigravity -> when (this) {
        AgentSandboxMode.ReadOnly -> "Antigravity runs in plan mode with terminal sandboxing enabled."
        AgentSandboxMode.WorkspaceWrite -> "Antigravity accepts edits without its plan-mode sandbox."
        AgentSandboxMode.None -> "Antigravity skips its permission checks."
    }
    AgentKind.OpenCode -> when (this) {
        AgentSandboxMode.ReadOnly -> "OpenCode runs its plan agent and keeps edit tools restricted."
        AgentSandboxMode.WorkspaceWrite -> "OpenCode asks before running tools that change the workspace."
        AgentSandboxMode.None -> "OpenCode auto-approves tool use (yolo / --auto)."
    }
    AgentKind.Pi -> when (this) {
        AgentSandboxMode.ReadOnly -> "Pi has no native sandbox; Andy asks it to inspect only and write a plan."
        AgentSandboxMode.WorkspaceWrite -> "Pi runs with its default tools (read, write, edit, bash)."
        AgentSandboxMode.None -> "Pi runs unrestricted; there is no native permission UI to skip."
    }
    AgentKind.Hermes -> when (this) {
        AgentSandboxMode.ReadOnly -> "Hermes receives a read-only instruction and restricted toolsets."
        AgentSandboxMode.WorkspaceWrite -> "Hermes uses its standard toolsets and approval behavior."
        AgentSandboxMode.None -> "Hermes runs with --yolo; provider-side safeguards still apply."
    }
    AgentKind.OpenClaw -> when (this) {
        AgentSandboxMode.ReadOnly -> "OpenClaw receives a read-only instruction."
        AgentSandboxMode.WorkspaceWrite -> "OpenClaw keeps its native approval prompts."
        AgentSandboxMode.None -> "OpenClaw auto-approves where local mode allows; it does not silently bypass safeguards."
    }
}

/**
 * The effort requested from a provider. Not every provider supports every value;
 * [AgentModelCatalog] exposes only the combinations documented for its CLI.
 */
enum class AgentReasoningEffort(val label: String, val cliValue: String) {
    None("none", "none"),
    Minimal("minimal", "minimal"),
    Low("low", "low"),
    Medium("medium", "medium"),
    High("high", "high"),
    ExtraHigh("extra high", "xhigh"),
    Max("max", "max"),
    Ultracode("ultracode", "ultracode"),
}

/** Claude Code aliases that accept the full effort dial Andy exposes. */
private val ClaudeCodeEfforts = listOf(
    AgentReasoningEffort.Low,
    AgentReasoningEffort.Medium,
    AgentReasoningEffort.High,
    AgentReasoningEffort.ExtraHigh,
    AgentReasoningEffort.Max,
    AgentReasoningEffort.Ultracode,
)

data class AgentModelOption(
    /** Model identifier passed to the provider CLI, before any provider-specific variant syntax. */
    val id: String,
    val label: String,
    val efforts: List<AgentReasoningEffort>,
    val supportsFastMode: Boolean = false,
    /** True when the provider CLI only advertises a `*-fast` slug for this base model. */
    val fastRequired: Boolean = false,
    /**
     * Provider-specific effort suffix tokens when they differ from [AgentReasoningEffort.cliValue]
     * (for example Cursor's `extra-high` vs Codex's `xhigh`).
     */
    val effortTokens: Map<AgentReasoningEffort, String> = emptyMap(),
) {
    /** Preferred effort when the CLI requires a suffix and the user left effort unset. */
    fun preferredEffort(): AgentReasoningEffort? =
        efforts.firstOrNull { it == AgentReasoningEffort.High } ?: efforts.firstOrNull()

    fun effortToken(effort: AgentReasoningEffort): String =
        effortTokens[effort] ?: effort.cliValue
}

/**
 * Offline fallback catalog for providers that cannot list models, plus a safety net when a
 * probe fails. Live lists from [AgentRunService.providerModels] take precedence in the UI.
 */
object AgentModelCatalog {
    private var liveDiscovered: Map<AgentKind, List<AgentModelOption>> = emptyMap()

    /** Latest successful provider probes; used when composing CLI model ids. */
    fun discovered(): Map<AgentKind, List<AgentModelOption>> = liveDiscovered

    fun publishDiscovered(models: Map<AgentKind, List<AgentModelOption>>) {
        liveDiscovered = models
    }

    fun options(agent: AgentKind): List<AgentModelOption> = when (agent) {
        AgentKind.Codex -> listOf(
            AgentModelOption("gpt-5.6-sol", "GPT-5.6 Sol", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh, AgentReasoningEffort.Max)),
            AgentModelOption("gpt-5.6-terra", "GPT-5.6 Terra", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("gpt-5.6-luna", "GPT-5.6 Luna", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("gpt-5.2-codex", "GPT-5.2-Codex", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh)),
        )
        AgentKind.ClaudeCode -> listOf(
            AgentModelOption("opus", "Opus", ClaudeCodeEfforts),
            AgentModelOption("sonnet", "Sonnet", ClaudeCodeEfforts),
            AgentModelOption("haiku", "Haiku", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("fable", "Fable", ClaudeCodeEfforts),
        )
        AgentKind.Cursor -> listOf(
            AgentModelOption("auto", "Auto", emptyList()),
            AgentModelOption("composer-2.5", "Composer 2.5", emptyList(), supportsFastMode = true),
            AgentModelOption("claude-opus-4-8", "Opus 4.8", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh), supportsFastMode = true),
            AgentModelOption("gpt-5.6-sol", "GPT-5.6 Sol", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh), supportsFastMode = true),
            AgentModelOption("gemini-3.1-pro", "Gemini 3.1 Pro", emptyList()),
            AgentModelOption("cursor-grok-4.5", "Grok 4.5", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High), supportsFastMode = true),
        )
        AgentKind.Antigravity -> listOf(
            AgentModelOption("gemini-3.6-flash", "Gemini 3.6 Flash", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("gemini-3.5-flash", "Gemini 3.5 Flash", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("gemini-3.1-pro", "Gemini 3.1 Pro", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.High)),
            AgentModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6", emptyList()),
            AgentModelOption("claude-opus-4-6-thinking", "Claude Opus 4.6", emptyList()),
            AgentModelOption("gpt-oss-120b", "GPT-OSS 120B", listOf(AgentReasoningEffort.Medium)),
        )
        AgentKind.OpenCode -> listOf(
            AgentModelOption("opencode/gpt-5.4-mini", "GPT-5.4 Mini (Zen)", emptyList()),
            AgentModelOption("anthropic/claude-sonnet-5", "Claude Sonnet 5", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("anthropic/claude-opus-4-8", "Claude Opus 4.8", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("openai/gpt-5.5", "GPT-5.5", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("google/gemini-3.1-pro", "Gemini 3.1 Pro", emptyList()),
        )
        AgentKind.Pi -> listOf(
            AgentModelOption(
                "openai-codex/gpt-5.5",
                "GPT-5.5 (openai-codex)",
                listOf(
                    AgentReasoningEffort.None,
                    AgentReasoningEffort.Minimal,
                    AgentReasoningEffort.Low,
                    AgentReasoningEffort.Medium,
                    AgentReasoningEffort.High,
                    AgentReasoningEffort.ExtraHigh,
                    AgentReasoningEffort.Max,
                ),
            ),
            AgentModelOption(
                "openai-codex/gpt-5.6-sol",
                "GPT-5.6 Sol (openai-codex)",
                listOf(
                    AgentReasoningEffort.None,
                    AgentReasoningEffort.Minimal,
                    AgentReasoningEffort.Low,
                    AgentReasoningEffort.Medium,
                    AgentReasoningEffort.High,
                    AgentReasoningEffort.ExtraHigh,
                    AgentReasoningEffort.Max,
                ),
            ),
            AgentModelOption(
                "anthropic/claude-sonnet-4-5",
                "Claude Sonnet 4.5",
                listOf(
                    AgentReasoningEffort.None,
                    AgentReasoningEffort.Minimal,
                    AgentReasoningEffort.Low,
                    AgentReasoningEffort.Medium,
                    AgentReasoningEffort.High,
                    AgentReasoningEffort.ExtraHigh,
                    AgentReasoningEffort.Max,
                ),
            ),
            AgentModelOption("google/gemini-2.5-pro", "Gemini 2.5 Pro", emptyList()),
        )
        AgentKind.Hermes -> listOf(
            AgentModelOption("anthropic/claude-sonnet-4", "Claude Sonnet 4", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("openai/gpt-5.5", "GPT-5.5", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
        )
        AgentKind.OpenClaw -> listOf(
            AgentModelOption("openai/gpt-5.6-sol", "GPT-5.6 Sol", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6", emptyList()),
        )
    }

    fun options(agent: AgentKind, discovered: Map<AgentKind, List<AgentModelOption>>): List<AgentModelOption> =
        discovered[agent]?.takeIf { it.isNotEmpty() } ?: options(agent)

    fun option(agent: AgentKind, id: String?, discovered: Map<AgentKind, List<AgentModelOption>> = emptyMap()): AgentModelOption? =
        id?.let { modelId ->
            val normalized = normalizeModelId(agent, modelId)
            options(agent, discovered).firstOrNull { it.id == normalized }
                ?: options(agent).firstOrNull { it.id == normalized }
        }
}

/** Normalize persisted catalog labels / full variants to the base model id Andy stores. */
internal fun normalizeModelId(agent: AgentKind, selected: String): String = when (agent) {
    AgentKind.Cursor -> cursorModelBaseId(selected)
    AgentKind.Antigravity -> antigravityModelBaseId(selected)
    else -> selected
}

/**
 * Cursor CLI model IDs are kebab-case slugs (`cursor-grok-4.5-high-fast`), not display names.
 * Map legacy catalog labels so persisted tasks/defaults still resolve.
 */
internal fun cursorModelBaseId(selected: String): String = when (selected) {
    "Auto", "auto" -> "auto"
    "Composer 2.5", "composer-2.5" -> "composer-2.5"
    "Opus 4.8", "claude-opus-4-8" -> "claude-opus-4-8"
    "GPT-5.6 Sol", "gpt-5.6-sol" -> "gpt-5.6-sol"
    "Gemini 3.1 Pro", "gemini-3.1-pro" -> "gemini-3.1-pro"
    "Grok 4.5", "cursor-grok-4.5" -> "cursor-grok-4.5"
    else -> stripProviderModelVariant(selected).baseId
}

/** Map legacy Antigravity display names and full effort slugs to the base id Andy stores. */
internal fun antigravityModelBaseId(selected: String): String = when (selected) {
    "Gemini 3.6 Flash", "gemini-3.6-flash" -> "gemini-3.6-flash"
    "Gemini 3.5 Flash", "gemini-3.5-flash" -> "gemini-3.5-flash"
    "Gemini 3.1 Pro", "gemini-3.1-pro" -> "gemini-3.1-pro"
    "Claude Sonnet 4.6", "claude-sonnet-4-6" -> "claude-sonnet-4-6"
    "Claude Opus 4.6", "claude-opus-4-6", "claude-opus-4-6-thinking" -> "claude-opus-4-6-thinking"
    "GPT-OSS 120B", "gpt-oss-120b" -> "gpt-oss-120b"
    else -> stripProviderModelVariant(selected).baseId
}

/**
 * Unified live badge and task lifecycle status.
 *
 * Queued (pre-launch) tasks have [AgentTask.status] null — derive from [AgentTask.isQueued].
 * Recovery/pre-run edge cases use [AgentTask.stoppedByUser], [AgentTask.resumable],
 * and [AgentTask.interrupted] rather than extra enum values.
 */
enum class AgentStatus {
    /** Agent actively running (green). */
    Working,
    /** Paused mid-task, needs input/approval to continue (rust/amber). */
    Blocked,
    /** Turn finished, your move — stable until the next message (cyan). */
    Done,
    /** Crashed / non-zero exit / failed (red). */
    Error,
}

/** Result of mapping a legacy persisted status string to the unified model. */
data class LegacyStatusMigration(
    val status: AgentStatus?,
    val stoppedByUser: Boolean = false,
    val resumable: Boolean = false,
    val interrupted: Boolean = false,
)

/** Maps legacy persisted task status names to the unified model. */
fun migrateLegacyTaskStatus(legacy: String): LegacyStatusMigration = when (legacy) {
    "Queued" -> LegacyStatusMigration(null)
    "Running" -> LegacyStatusMigration(AgentStatus.Working)
    "WaitingForInput" -> LegacyStatusMigration(AgentStatus.Blocked)
    "Completed" -> LegacyStatusMigration(AgentStatus.Done)
    "Failed" -> LegacyStatusMigration(AgentStatus.Error)
    "Stopped" -> LegacyStatusMigration(AgentStatus.Done, stoppedByUser = true)
    "Paused" -> LegacyStatusMigration(AgentStatus.Done, resumable = true)
    "Unknown" -> LegacyStatusMigration(AgentStatus.Error, interrupted = true)
    else -> LegacyStatusMigration(null)
}

/** A follow-up held until the agent's current response completes. */
data class AgentQueuedFollowUp(
    val text: String,
    val imagePaths: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
    /** Managed evidence bundle ids (§4) attached to this follow-up, if any. */
    val contextBundleIds: List<String> = emptyList(),
    /** Where this follow-up's contextual action was triggered from, if any. */
    val provenance: AgentContextualProvenance? = null,
)

/** One selectable answer supplied by an agent when it needs a product decision. */
data class AgentUserInputOption(
    val label: String,
    val description: String = "",
)

/** A concise question rendered by Andy as choices plus an always-available freeform answer. */
data class AgentUserInputQuestion(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<AgentUserInputOption>,
)

enum class AgentUserInputOrigin {
    Artifact,
    AcpPermission,
}

/** A persisted decision checkpoint emitted by a provider-neutral agent protocol. */
data class AgentUserInputRequest(
    val id: String,
    val questions: List<AgentUserInputQuestion>,
    val origin: AgentUserInputOrigin = AgentUserInputOrigin.Artifact,
)

data class AgentTask(
    val id: String,
    val title: String,
    val prompt: String,
    val agent: AgentKind,
    val projectId: String? = null,
    /** Directory the agent process runs in (the worktree path when isolated), if it has project context. */
    val cwd: String? = null,
    /** The project/repo directory the task was created against, if it has project context. */
    val originDir: String? = null,
    val useWorktree: Boolean = false,
    val worktreePath: String? = null,
    val branchName: String? = null,
    val attachAndyMcp: Boolean = false,
    val autonomy: AgentAutonomy = AgentAutonomy.Standard,
    /** Explicit provider sandbox/permission choice. Null preserves the legacy mapping from [autonomy]. */
    val sandboxMode: AgentSandboxMode? = null,
    /** Ask the provider to inspect and propose work without making changes. */
    val planMode: Boolean = false,
    /** ACP-lane only: surface every tool call (including reads) for approval instead of auto-allowing it. */
    val confirmToolCalls: Boolean = false,
    /** Final response from a successful plan-mode run, retained for project workflow handoff. */
    val completedPlanText: String? = null,
    /** A fresh-provider continuation used only when that provider cannot resume its prior session. */
    val continuationPrompt: String? = null,
    /** Null keeps the provider's own default model. */
    val model: String? = null,
    /** Null keeps the provider's own default reasoning level. */
    val reasoningEffort: AgentReasoningEffort? = null,
    /** Cursor-only: request the provider's Fast variant when it has one. */
    val fastMode: Boolean = false,
    /** OpenClaw-only: start in an Andy-scoped session instead of the shared default main session. */
    val openClawNewSession: Boolean = true,
    /** Local images supplied with the original task prompt. */
    val imagePaths: List<String> = emptyList(),
    /** Local skills selected while composing the original prompt. */
    val skills: List<AgentSkill> = emptyList(),
    /** A durable objective Andy keeps alongside the provider session. */
    val goal: String? = null,
    /** Follow-ups to send in order after this task's current successful run completes. */
    val queuedFollowUps: List<AgentQueuedFollowUp> = emptyList(),
    /** An explicit decision checkpoint that must be answered before this task can continue. */
    val userInputRequest: AgentUserInputRequest? = null,
    val maxBudgetUsd: Double? = null,
    /** Git tree hash snapshotting the full working tree when the task began; changes are diffed against it. */
    val changeBaselineTree: String? = null,
    /** Immutable repository changes captured when this chat last finished. */
    val completedChanges: AgentThreadChangeSnapshot? = null,
    /** Null while pre-launch ([isQueued]); otherwise the source of truth for badge + notifications. */
    val status: AgentStatus? = null,
    /** User explicitly stopped the run; [status] is [AgentStatus.Done]. */
    val stoppedByUser: Boolean = false,
    /** Idle at prompt when the app quit; [status] is [AgentStatus.Done] and session can resume. */
    val resumable: Boolean = false,
    /** Was active when the app restarted and the process is gone; [status] is [AgentStatus.Error]. */
    val interrupted: Boolean = false,
    /**
     * True when [status] was set from a high-confidence source (hook, file protocol,
     * process exit, question artifact). OS notifications fire only on confident transitions.
     */
    val statusConfident: Boolean = false,
    val vendorSessionId: String? = null,
    /** ACP session ids are a different namespace from vendor CLI session ids. */
    val acpSessionId: String? = null,
    /** Last ACP prompt stop reason, retained for diagnostics and recovery. */
    val stopReason: String? = null,
    /** Transport lane is fixed at creation and remains stable across resume. */
    val lane: AgentLaneKind = AgentLaneKind.Terminal,
    val createdAtMillis: Long,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val totalCostUsd: Double? = null,
    /** Whether [totalCostUsd] is estimated from published token rates rather than reported by the provider. */
    val costIsEstimated: Boolean = false,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** Tokens currently retained in the agent's active context window, when the provider reports it. */
    val contextTokens: Long? = null,
    /** Maximum active-context capacity reported by the provider for this chat. */
    val contextWindowTokens: Long? = null,
    /** True after an agent finishes until the chat is opened (or marked read). */
    val unread: Boolean = false,
    /** Hidden from the default chat list until unarchived. */
    val archived: Boolean = false,
    /** True when automatic retention reduced this task's transcript directory to archive.zip. */
    val transcriptCompressed: Boolean = false,
    /** True only for the run that created and may remove [worktreePath]. */
    val ownsWorktree: Boolean = false,
    /** Task id whose worktree branch this task's worktree was forked from. Null for root worktrees and non-worktree tasks. */
    val parentWorktreeTaskId: String? = null,
    /** Optional typed project task that launched this raw agent session. */
    val workflowTaskId: String? = null,
    val workflowStage: ProjectWorkflowStage? = null,
    val workflowAttempt: Int? = null,
    /** Most recent follow-up prompt sent to the task (null when only initial prompt exists). */
    val latestPrompt: String? = null,
    /** Final provider response for non-plan workflow stages and completed chats. */
    val completedResultText: String? = null,
    /** Managed evidence bundle ids (§4) attached to this task, if any. Never arbitrary paths. */
    val contextBundleIds: List<String> = emptyList(),
    /** Where this task's contextual action was triggered from, if launched from one. */
    val provenance: AgentContextualProvenance? = null,
    /**
     * Desktop-only: prompt text pointing at [contextBundleIds] after they were copied into this
     * task's local evidence directory. Recomputed at each launch/resume, so it does not need to
     * survive a restart — null simply omits the hint until the next materialization.
     */
    val evidenceLocalPathsHint: String? = null,
) {
    /** True only before launch — both [status] and [startedAtMillis] are still unset. */
    val isQueued: Boolean get() = status == null && startedAtMillis == null
    val isActive: Boolean get() = status == AgentStatus.Working || status == AgentStatus.Blocked
    val needsInput: Boolean get() = status == AgentStatus.Blocked || userInputRequest != null
    val notificationTitle: String
        get() {
            val text = latestPrompt?.takeIf { it.isNotBlank() } ?: return title
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= 60) flat else flat.take(59) + "…"
        }
}

/** A provider-reported account limit window. Percentages are absent when a CLI only reports its reset time. */
data class AgentQuotaWindow(
    val label: String,
    val remainingFraction: Float? = null,
    val resetAtMillis: Long? = null,
    val detail: String? = null,
)

enum class AgentQuotaSource(val label: String) {
    ProviderQuery("account query"),
    ProviderEvent("agent event"),
}

/** Explicit consent for provider-specific local account sources. All sensitive sources default off. */
data class AgentQuotaAccess(
    val claudeAccountAccess: Boolean = false,
    val cursorAccountAccess: Boolean = false,
    val antigravityAccountAccess: Boolean = false,
) {
    fun allows(agent: AgentKind): Boolean = when (agent) {
        AgentKind.Codex -> true
        AgentKind.ClaudeCode -> claudeAccountAccess
        AgentKind.Cursor -> cursorAccountAccess
        AgentKind.Antigravity -> antigravityAccountAccess
        // Multi-provider auth; no stable quota probe yet.
        AgentKind.OpenCode, AgentKind.Pi, AgentKind.Hermes, AgentKind.OpenClaw -> false
    }

    fun withAccess(agent: AgentKind, enabled: Boolean): AgentQuotaAccess = when (agent) {
        AgentKind.Codex, AgentKind.OpenCode, AgentKind.Pi, AgentKind.Hermes, AgentKind.OpenClaw -> this
        AgentKind.ClaudeCode -> copy(claudeAccountAccess = enabled)
        AgentKind.Cursor -> copy(cursorAccountAccess = enabled)
        AgentKind.Antigravity -> copy(antigravityAccountAccess = enabled)
    }
}

/** The newest live quota data seen from each provider while Andy is running. */
data class AgentProviderQuota(
    val windows: List<AgentQuotaWindow>,
    val updatedAtMillis: Long,
    val source: AgentQuotaSource = AgentQuotaSource.ProviderEvent,
    val accountLabel: String? = null,
    val lifetimeTokens: Long? = null,
    /** Provider-reported daily token buckets, oldest to newest, when that account API exposes them. */
    val providerTokenDays: List<Long> = emptyList(),
)

/** Local activity summary used alongside a provider's live account limits. */
data class AgentUsageOverview(
    val runsLast24Hours: Int,
    val runsLast30Days: Int,
    val tokensLast24Hours: Long,
    val tokensLast30Days: Long,
    val costLast24Hours: Double,
    val costLast30Days: Double,
    val topModel: String?,
    /** Seven oldest-to-newest daily token totals, for the compact activity histogram. */
    val tokenDays: List<Long>,
)

fun agentUsageOverview(tasks: List<AgentTask>, agent: AgentKind, nowMillis: Long): AgentUsageOverview {
    val providerTasks = tasks.filter { it.agent == agent }
    val day = 24L * 60L * 60L * 1000L
    fun tokens(task: AgentTask): Long = (task.inputTokens ?: 0L) + (task.outputTokens ?: 0L)
    fun cost(task: AgentTask): Double = task.totalCostUsd ?: 0.0
    fun inWindow(windowMillis: Long): List<AgentTask> = providerTasks.filter { it.createdAtMillis >= nowMillis - windowMillis }
    val last24 = inWindow(day)
    val last30 = inWindow(day * 30)
    val topModel = last30
        .groupingBy { it.model ?: "provider default" }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
    return AgentUsageOverview(
        runsLast24Hours = last24.size,
        runsLast30Days = last30.size,
        tokensLast24Hours = last24.sumOf(::tokens),
        tokensLast30Days = last30.sumOf(::tokens),
        costLast24Hours = last24.sumOf(::cost),
        costLast30Days = last30.sumOf(::cost),
        topModel = topModel,
        tokenDays = (6 downTo 0).map { offset ->
            val end = nowMillis - offset * day
            providerTasks.filter { it.createdAtMillis in (end - day) until end }.sumOf(::tokens)
        },
    )
}

data class AgentTaskDraft(
    val title: String,
    val prompt: String,
    val agent: AgentKind,
    val projectId: String?,
    val directory: String? = null,
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val autonomy: AgentAutonomy = AgentAutonomy.Standard,
    val sandboxMode: AgentSandboxMode? = null,
    val planMode: Boolean = false,
    val confirmToolCalls: Boolean = false,
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val openClawNewSession: Boolean = true,
    val imagePaths: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
    val goal: String? = null,
    val maxBudgetUsd: Double? = null,
    /** Reuse an existing workflow worktree instead of creating a new one. */
    val existingWorktreePath: String? = null,
    val existingBranchName: String? = null,
    /** When set (and useWorktree = true), the new worktree forks from this task's branch instead of originDir's current HEAD. */
    val baseWorktreeTaskId: String? = null,
    val workflowTaskId: String? = null,
    val workflowStage: ProjectWorkflowStage? = null,
    val workflowAttempt: Int? = null,
    /** Managed evidence bundle ids (§4) to attach to the launched task, if any. */
    val contextBundleIds: List<String> = emptyList(),
    /** Where this task's contextual action was triggered from, if launched from one. */
    val provenance: AgentContextualProvenance? = null,
    /** Optional explicit lane override used by tests and rollout controls. */
    val lane: AgentLaneKind? = null,
)

/** A lightweight candidate for the composer's "base on" picker. */
data class WorktreeBaseOption(
    val taskId: String,
    val title: String,
    val branch: String,
    val path: String,
)

/** One node in the reconciled worktree tree for a repo. */
data class WorktreeNode(
    val path: String,
    val branch: String?,
    val isMain: Boolean,
    val taskId: String?,
    val taskTitle: String?,
    val parentTaskId: String?,
    val tracked: Boolean,
)

/** Outcome of a delete attempt that may be blocked by child worktrees. */
sealed interface WorktreeDeleteOutcome {
    data object Deleted : WorktreeDeleteOutcome
    data class BlockedByChildren(val children: List<WorktreeBaseOption>) : WorktreeDeleteOutcome
}

/** Outcome of applying a worktree branch into a target working tree. */
sealed interface WorktreeMergeOutcome {
    /** Changes applied; HEAD unchanged. */
    data object Applied : WorktreeMergeOutcome
    /**
     * Merge stopped with conflicts; conflict markers remain in the target dir.
     * Caller must leave them for the user or call abort.
     */
    data class Conflicts(val detail: String) : WorktreeMergeOutcome
    /** Failed without leaving a merge in progress. */
    data class Failed(val detail: String) : WorktreeMergeOutcome
}

/** Last-used launch settings, stored independently for each provider. */
data class AgentProviderDefaults(
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val openClawNewSession: Boolean = true,
    val autonomy: AgentAutonomy = AgentAutonomy.Standard,
    val sandboxMode: AgentSandboxMode? = null,
    val planMode: Boolean = false,
    val confirmToolCalls: Boolean = false,
    val useWorktree: Boolean = false,
    val attachAndyMcp: Boolean = false,
    val maxBudgetUsd: Double? = null,
)

/** A locally installed agent skill that can be attached to a follow-up prompt. */
data class AgentSkill(
    val name: String,
    val description: String,
    /** Absolute path to the skill's SKILL.md, so the agent and chat link use the same source. */
    val path: String,
    /**
     * When false, the skill is kept for provider reference loading but omitted from Andy's
     * composer slash menu (honors SKILL.md `user-invocable: false`).
     */
    val userInvocable: Boolean = true,
)

/** A command implemented by Andy rather than forwarded as literal prompt text to a provider CLI. */
data class AgentNativeSlashCommand(
    val name: String,
    val description: String,
)

/**
 * Interactive CLI slash commands are not available to the non-interactive
 * provider runners Andy uses. Keep this list deliberately limited to commands
 * Andy implements with equivalent, persisted behavior.
 */
object AgentNativeSlashCommands {
    fun forAgent(agent: AgentKind): List<AgentNativeSlashCommand> = when (agent) {
        AgentKind.Codex, AgentKind.ClaudeCode -> listOf(
            AgentNativeSlashCommand("goal", "set or clear this task's persistent goal"),
        )
        else -> emptyList()
    }

    fun supportsGoal(agent: AgentKind): Boolean = agent == AgentKind.Codex || agent == AgentKind.ClaudeCode
}

/** Andy-native commands plus provider-advertised slash commands for composer autocomplete. */
fun mergedComposerSlashCommands(
    agent: AgentKind,
    providerCommands: List<AgentSlashCommand>,
): List<AgentNativeSlashCommand> =
    (AgentNativeSlashCommands.forAgent(agent) + providerCommands.map { AgentNativeSlashCommand(it.name, it.description) })
        .distinctBy { it.name.normalizedComposerSlashName() }

/**
 * Disk-discovered skills that are not already advertised as provider slash commands.
 * Providers like Claude/Cursor surface installed skills via ACP; listing them again as
 * Andy skills produces green+blue duplicates in the composer menu.
 */
fun composerSkillsForSlashMenu(
    skills: List<AgentSkill>,
    commands: List<AgentNativeSlashCommand>,
): List<AgentSkill> {
    if (skills.isEmpty()) return emptyList()
    val invocable = skills.filter { it.userInvocable }
    if (invocable.isEmpty()) return emptyList()
    if (commands.isEmpty()) return invocable
    val commandNames = commands.mapTo(linkedSetOf()) { it.name.normalizedComposerSlashName() }
    return invocable.filter { it.name.normalizedComposerSlashName() !in commandNames }
}

internal fun String.normalizedComposerSlashName(): String =
    trim().trimStart('/', '$').lowercase()

enum class AgentGoalCommandAction { Set, Clear }

data class AgentGoalCommand(
    val action: AgentGoalCommandAction,
    val goal: String? = null,
    /** Any prompt text after the first command line. */
    val remainingPrompt: String = "",
)

/** Parses Andy's native `/goal <objective>` and `/goal clear` syntax. */
fun String.parseAgentGoalCommand(): AgentGoalCommand? {
    val lines = trim().lines()
    val firstLine = lines.firstOrNull()?.trim().orEmpty()
    if (!firstLine.startsWith("/goal") || (firstLine.length > 5 && !firstLine[5].isWhitespace())) return null
    val argument = firstLine.removePrefix("/goal").trim()
    return if (argument.equals("clear", ignoreCase = true)) {
        AgentGoalCommand(AgentGoalCommandAction.Clear, remainingPrompt = lines.drop(1).joinToString("\n").trim())
    } else {
        argument.takeIf { it.isNotBlank() }?.let { goal ->
            AgentGoalCommand(AgentGoalCommandAction.Set, goal, lines.drop(1).joinToString("\n").trim())
        }
    }
}

data class AgentFileChange(
    val path: String,
    val additions: Int,
    val deletions: Int,
)

data class AgentChangeSummary(val files: List<AgentFileChange>) {
    val additions: Int get() = files.sumOf { it.additions }
    val deletions: Int get() = files.sumOf { it.deletions }
}

/** The exact change set produced by a chat, kept so later repository edits do not leak into it. */
data class AgentThreadChangeSnapshot(
    val summary: AgentChangeSummary,
    val diffs: Map<String, AgentFileDiff>,
)

enum class DiffLineKind { Context, Addition, Deletion }

data class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

data class AgentFileDiff(
    val path: String,
    val lines: List<DiffLine>,
    val additions: Int = lines.count { it.kind == DiffLineKind.Addition },
    val deletions: Int = lines.count { it.kind == DiffLineKind.Deletion },
    val isBinary: Boolean = false,
    val isNewFile: Boolean = false,
)

fun AgentTaskDraft.providerDefaults(): AgentProviderDefaults = AgentProviderDefaults(
    model = model,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
    openClawNewSession = openClawNewSession,
    autonomy = autonomy,
    sandboxMode = sandboxMode,
    planMode = planMode,
    confirmToolCalls = confirmToolCalls,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)

fun AgentTask.providerDefaults(): AgentProviderDefaults = AgentProviderDefaults(
    model = model,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
    openClawNewSession = openClawNewSession,
    autonomy = autonomy,
    sandboxMode = sandboxMode,
    planMode = planMode,
    confirmToolCalls = confirmToolCalls,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)

/** Provider-specific model string passed by the adapter. */
fun AgentTask.modelForCli(discovered: Map<AgentKind, List<AgentModelOption>> = AgentModelCatalog.discovered()): String? = model?.let { selected ->
    when (agent) {
        AgentKind.Cursor -> {
            val base = cursorModelBaseId(selected)
            val catalog = AgentModelCatalog.option(AgentKind.Cursor, base, discovered)
            buildString {
                append(base)
                // Cursor variants bake effort into the model id; bare bases like cursor-grok-4.5 are rejected.
                val effort = reasoningEffort ?: catalog?.preferredEffort()
                if (effort != null && catalog?.efforts?.isNotEmpty() == true) {
                    append('-').append(catalog.effortToken(effort))
                }
                // Catalog options gate -fast; fast-only models always keep the suffix.
                if (catalog?.supportsFastMode == true && (fastMode || catalog.fastRequired)) append("-fast")
            }
        }
        AgentKind.Antigravity -> {
            val base = antigravityModelBaseId(selected)
            val catalog = AgentModelCatalog.option(AgentKind.Antigravity, base, discovered)
            val effort = reasoningEffort
            when {
                effort == null -> if (selected.any { it.isWhitespace() }) selected else base
                // Legacy display-name tasks keep the "(High)" variant syntax.
                selected.any { it.isWhitespace() } -> {
                    val level = effort.label.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
                    "$selected ($level)"
                }
                else -> "$base-${catalog?.effortToken(effort) ?: effort.cliValue}"
            }
        }
        // Pi requires provider/model (e.g. openai-codex/gpt-5.5). A bare provider
        // column from a bad --list-models parse must not be passed as --model.
        AgentKind.Pi -> selected.takeIf { '/' in it }
        else -> selected
    }
}

fun AgentTask.modelConfigurationLabel(): String = buildList {
    model?.let(::add) ?: add("provider default")
    reasoningEffort?.let { add(it.label) }
    if (fastMode) add("fast")
    if (agent == AgentKind.OpenClaw) add(if (openClawNewSession) "new session" else "main session")
    if (planMode) add("plan")
}.joinToString(" · ")

/** Text-only CLIs receive image paths without rewriting stored user messages. */
fun promptWithImageHints(text: String, imagePaths: List<String>): String = if (imagePaths.isEmpty()) {
    text
} else {
    buildString {
        append(text)
        append("\n\nAttached image file")
        if (imagePaths.size != 1) append('s')
        append(" (inspect these as part of the task):\n")
        imagePaths.forEach { append("- ").append(it).append('\n') }
    }.trimEnd()
}

/** Inline image paths for typing into a live PTY where multiline paste needs two Enters. */
fun promptWithInlineImageHints(text: String, imagePaths: List<String>): String = if (imagePaths.isEmpty()) {
    text
} else {
    val paths = imagePaths.joinToString(", ")
    when {
        text.isBlank() -> "Attached image files (inspect as part of the task): $paths"
        else -> "$text — attached image files (inspect as part of the task): $paths"
    }
}

/** Gives provider CLIs a concrete, portable pointer to the selected local instructions. */
fun promptWithSkillHints(text: String, skills: List<AgentSkill>): String = if (skills.isEmpty()) {
    text
} else {
    buildString {
        append(text)
        append("\n\nUse these local skill instructions before responding:\n")
        skills.distinctBy { it.path }.forEach { skill ->
            append("- ").append(skill.name).append(": ").append(skill.path).append('\n')
        }
    }.trimEnd()
}

/** Keeps an Andy-native goal visible to each provider without relying on TUI-only slash parsing. */
fun promptWithGoalHint(text: String, goal: String?): String = goal?.takeIf { it.isNotBlank() }?.let { activeGoal ->
    "$text\n\nPersistent task goal: $activeGoal\nKeep this goal in mind throughout the task."
} ?: text

/** Basename for a local file path, tolerating both slash styles. */
fun localPathFileName(path: String): String =
    path.trim().substringAfterLast('/').substringAfterLast('\\')

/** Derives a list title when the composer leaves [AgentTaskDraft.title] blank. */
fun AgentTaskDraft.fallbackTitle(): String = when {
    prompt.isNotBlank() -> prompt.replace('\n', ' ').trim()
    imagePaths.isNotEmpty() -> {
        val first = localPathFileName(imagePaths.first())
        if (imagePaths.size == 1) first else "$first (+${imagePaths.size - 1})"
    }
    else -> ""
}

private fun promptWithPlanModeHint(text: String, planMode: Boolean, grilling: Boolean = false): String = when {
    !planMode -> text
    grilling -> {
        "$text\n\nPlan mode is active, but grill-me is in progress. Explore and analyze as needed, " +
            "but defer the full implementation plan until shared understanding is reached. " +
            "Do not edit files, apply patches, or run commands that modify the workspace."
    }
    else -> {
        "$text\n\nPlan mode is active. Inspect and analyze the task, then return a concrete implementation plan. " +
            "Do not edit files, apply patches, or run commands that modify the workspace."
    }
}

private fun promptWithGrillMeHint(text: String, skills: List<AgentSkill>, taskId: String): String {
    if (!hasGrillMeSkills(skills)) return text
    return text + "\n\n" + grillMeChatPromptAddendum(".andy/$taskId")
}

private fun composeAgentPrompt(
    text: String,
    skills: List<AgentSkill>,
    taskId: String,
    planMode: Boolean,
    goal: String?,
): String {
    val grilling = hasGrillMeSkills(skills)
    return promptWithGrillMeHint(
        promptWithGoalHint(
            promptWithPlanModeHint(promptWithSkillHints(text, skills), planMode, grilling),
            goal,
        ),
        skills,
        taskId,
    )
}

/** Andy never resolves bundle ids to paths here — that happens at launch time, per adapter/cwd. */
fun promptWithEvidenceHint(text: String, contextBundleIds: List<String>): String = if (contextBundleIds.isEmpty()) {
    text
} else {
    buildString {
        append(text)
        append("\n\nManaged evidence bundle")
        if (contextBundleIds.size != 1) append('s')
        append(" (redacted investigation context under ~/.andy/evidence/):\n")
        contextBundleIds.forEach { append("- ").append(it).append('\n') }
    }.trimEnd()
}

/** Appends resolved local evidence paths (desktop-only) after they were copied for this launch. */
fun promptWithLocalEvidencePathsHint(text: String, hint: String?): String =
    if (hint.isNullOrBlank()) text else text + hint

fun AgentTask.promptForCli(): String = promptWithImageHints(
    promptWithLocalEvidencePathsHint(
        promptWithEvidenceHint(
            composeAgentPrompt(continuationPrompt ?: prompt, skills, id, planMode, goal),
            contextBundleIds,
        ),
        evidenceLocalPathsHint,
    ),
    imagePaths,
)

fun AgentTask.followUpPromptForCli(
    text: String,
    imagePaths: List<String>,
    skills: List<AgentSkill> = this.skills,
): String = promptWithImageHints(
    composeAgentPrompt(text, skills, id, planMode, goal),
    imagePaths,
)

/** Prompt and native image argv for a follow-up turn. */
data class FollowUpCliPayload(
    val prompt: String,
    val imagePaths: List<String> = emptyList(),
)

/**
 * Formats a follow-up for provider CLIs. Text-only CLIs receive file paths in the
 * prompt; Codex receives them separately for native `--image` flags.
 */
fun AgentTask.followUpCliPayload(
    text: String,
    imagePaths: List<String>,
    skills: List<AgentSkill> = emptyList(),
): FollowUpCliPayload {
    val composed = composeAgentPrompt(text, skills, id, planMode, goal)
    return when (agent) {
        AgentKind.Codex -> FollowUpCliPayload(
            prompt = composed,
            imagePaths = imagePaths,
        )
        else -> FollowUpCliPayload(
            prompt = promptWithImageHints(composed, imagePaths),
        )
    }
}

/** Compact prompt for typing into a live interactive PTY from Andy's composer. */
fun AgentTask.followUpPromptForLiveTerminal(
    text: String,
    imagePaths: List<String>,
    skills: List<AgentSkill> = emptyList(),
): String = promptWithInlineImageHints(
    composeAgentPrompt(text, skills, id, planMode, goal),
    imagePaths,
)

private data class TokenPrice(val inputUsdPerMillion: Double, val outputUsdPerMillion: Double)

/**
 * Best-effort API-list-price estimate for CLIs that report token counts but no
 * billed total. Subscription credits, cached input, tools, and account-specific
 * pricing can differ, so callers must present this as an estimate.
 */
fun AgentTask.estimatedTokenCostUsd(inputTokens: Long?, outputTokens: Long?): Double? {
    if (inputTokens == null && outputTokens == null) return null
    val price = when (agent) {
        AgentKind.Codex -> TokenPrice(inputUsdPerMillion = 1.25, outputUsdPerMillion = 10.0)
        AgentKind.Cursor -> when {
            model?.equals("auto", ignoreCase = true) == true -> TokenPrice(inputUsdPerMillion = 1.25, outputUsdPerMillion = 6.0)
            model?.contains("opus", ignoreCase = true) == true -> TokenPrice(inputUsdPerMillion = 5.0, outputUsdPerMillion = 25.0)
            model?.contains("gpt", ignoreCase = true) == true || model?.contains("sol", ignoreCase = true) == true ->
                TokenPrice(inputUsdPerMillion = 1.25, outputUsdPerMillion = 10.0)
            else -> null
        }
        // Claude Code reports its billed total; these providers currently report no token usage.
        AgentKind.ClaudeCode, AgentKind.Antigravity, AgentKind.OpenCode, AgentKind.Pi,
        AgentKind.Hermes, AgentKind.OpenClaw -> null
    } ?: return null
    return ((inputTokens ?: 0) * price.inputUsdPerMillion + (outputTokens ?: 0) * price.outputUsdPerMillion) / 1_000_000.0
}

sealed interface AgentEvent {
    val atMillis: Long

    data class SessionStarted(override val atMillis: Long, val sessionId: String?, val model: String?) : AgentEvent
    data class AssistantText(
        override val atMillis: Long,
        val text: String,
        /** True for a fragment that should extend the preceding live message. */
        val isStreamDelta: Boolean = false,
    ) : AgentEvent
    data class Thinking(
        override val atMillis: Long,
        val text: String,
        /** True for a fragment that should extend the preceding live message. */
        val isStreamDelta: Boolean = false,
    ) : AgentEvent
    data class UserMessage(
        override val atMillis: Long,
        val text: String,
        /** Skills explicitly selected in this message, rendered as local links in the transcript. */
        val skills: List<AgentSkill> = emptyList(),
        /** Local image paths attached with this message, shown as thumbnails in the bubble. */
        val imagePaths: List<String> = emptyList(),
    ) : AgentEvent
    data class ToolCall(
        override val atMillis: Long,
        val toolName: String,
        val summary: String,
        val detail: String = summary,
        val toolCallId: String? = null,
        val kind: AgentToolKind? = null,
        val state: AgentToolState = AgentToolState.Completed,
        val locations: List<String> = emptyList(),
    ) : AgentEvent
    data class ToolResult(
        override val atMillis: Long,
        val toolName: String?,
        val summary: String,
        val detail: String = summary,
        val isError: Boolean,
        /** Optional live provider-limit metadata, kept with its transcript row. */
        val quotaWindows: List<AgentQuotaWindow> = emptyList(),
    ) : AgentEvent
    data class TaskError(override val atMillis: Long, val message: String) : AgentEvent
    data class TaskResult(
        override val atMillis: Long,
        val success: Boolean,
        val finalText: String?,
        val costUsd: Double? = null,
        val costIsEstimated: Boolean = false,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val durationMs: Long? = null,
    ) : AgentEvent

    /** A live snapshot of the active conversation context, distinct from per-turn billing usage. */
    data class ContextUsage(
        override val atMillis: Long,
        val usedTokens: Long? = null,
        val windowTokens: Long? = null,
    ) : AgentEvent

    data class PlanUpdate(
        override val atMillis: Long,
        val entries: List<AgentPlanEntry>,
        val markdown: String? = null,
    ) : AgentEvent
    data class ModeChanged(override val atMillis: Long, val modeId: String) : AgentEvent
    data class AvailableCommands(override val atMillis: Long, val commands: List<AgentSlashCommand>) : AgentEvent
    /** Emitted once when an ACP session opens, if the provider advertises switchable modes. */
    data class AvailableModes(
        override val atMillis: Long,
        val modes: List<AgentSessionMode>,
        val currentModeId: String?,
    ) : AgentEvent
    data class PermissionRequest(
        override val atMillis: Long,
        val requestId: String,
        val toolName: String,
        val question: String,
        val options: List<AgentUserInputOption>,
    ) : AgentEvent
    data class PermissionResolved(
        override val atMillis: Long,
        val requestId: String,
        val optionId: String,
        val allowed: Boolean,
        /** When set, Andy resolved the permission without a user prompt (policy, stop, queue). */
        val note: String? = null,
    ) : AgentEvent

    /** Fallback for stdout lines the adapter could not parse; nothing is dropped. */
    data class Raw(override val atMillis: Long, val line: String) : AgentEvent
}

enum class AgentToolKind {
    Read,
    Edit,
    Delete,
    Move,
    Search,
    Execute,
    Think,
    Fetch,
    Other,
}

enum class AgentToolState {
    Pending,
    InProgress,
    Completed,
    Failed,
}

data class AgentPlanEntry(
    val content: String,
    val status: String = "pending",
)

data class AgentSlashCommand(
    val name: String,
    val description: String = "",
    val inputHint: String? = null,
)

data class AgentSessionMode(
    val id: String,
    val name: String,
    val description: String? = null,
)

/** True when an ACP provider mode represents read-only planning rather than execution. */
fun AgentSessionMode.looksLikePlanMode(): Boolean {
    val normalizedId = id.trim().lowercase()
    val normalizedName = name.trim().lowercase()
    return normalizedId == "plan" || normalizedName == "plan" ||
        normalizedId.endsWith("-plan") || normalizedName.endsWith(" plan")
}

/**
 * True when the latest ACP [AgentEvent.PlanUpdate] still looks like an unconfirmed plan.
 * Cursor can emit Create Plan + plan entries and end the turn without flipping session mode.
 */
fun latestPlanHasPendingEntries(events: List<AgentEvent>): Boolean {
    val plan = events.asReversed().firstOrNull { it is AgentEvent.PlanUpdate } as? AgentEvent.PlanUpdate
        ?: return false
    if (plan.entries.isEmpty()) {
        // PlanRemoved clears entries; markdown-only plans still count as awaiting.
        return !plan.markdown.isNullOrBlank()
    }
    return plan.entries.any { entry ->
        when (entry.status.trim().lowercase()) {
            "completed", "complete", "done", "cancelled", "canceled", "file" -> false
            else -> true
        }
    }
}

/**
 * ACP providers sometimes emit whitespace-only text chunks. Older builds stored those as
 * [AgentEvent.Raw], which broke stream coalescing and dropped the whitespace itself.
 */
internal fun recoverAcpWhitespaceChunks(events: List<AgentEvent>): List<AgentEvent> {
    val out = mutableListOf<AgentEvent>()
    for (event in events) {
        if (event is AgentEvent.Raw) {
            val text = decodeAcpWhitespaceRaw(event.line) ?: run {
                out += event
                continue
            }
            val channel = out.lastOrNull { it.isStreamCoalesceTransparent().not() }
            when (channel) {
                is AgentEvent.Thinking ->
                    out += AgentEvent.Thinking(event.atMillis, text, isStreamDelta = true)
                else ->
                    out += AgentEvent.AssistantText(event.atMillis, text, isStreamDelta = true)
            }
            continue
        }
        out += event
    }
    return out
}

private val AcpWhitespaceRawPattern =
    Regex("""^Text\(text=(.*), annotations=.*\)$""")

private fun decodeAcpWhitespaceRaw(line: String): String? {
    val raw = AcpWhitespaceRawPattern.matchEntire(line.trim())?.groupValues?.getOrNull(1) ?: return null
    val decoded = buildString {
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '\\' && index + 1 < raw.length) {
                when (raw[index + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    else -> append(raw[index + 1])
                }
                index += 2
            } else {
                append(raw[index])
                index++
            }
        }
    }
    return decoded.takeIf { it.isNotEmpty() && it.all(Char::isWhitespace) }
}

private fun AgentEvent.isStreamCoalesceTransparent(): Boolean = when (this) {
    is AgentEvent.Raw,
    is AgentEvent.AvailableCommands,
    is AgentEvent.AvailableModes,
    is AgentEvent.ContextUsage,
    -> true
    else -> false
}

private fun AgentEvent.isStreamCoalesceBarrier(): Boolean = when (this) {
    is AgentEvent.UserMessage,
    is AgentEvent.ToolCall,
    is AgentEvent.ToolResult,
    is AgentEvent.TaskError,
    is AgentEvent.TaskResult,
    is AgentEvent.SessionStarted,
    is AgentEvent.PermissionRequest,
    is AgentEvent.PermissionResolved,
    is AgentEvent.PlanUpdate,
    is AgentEvent.ModeChanged,
    -> true
    is AgentEvent.AssistantText -> !isStreamDelta
    is AgentEvent.Thinking -> !isStreamDelta
    else -> false
}

/**
 * Prepare a persisted ACP transcript for in-memory display: recover whitespace-only
 * provider chunks, then fold stream deltas into one row per assistant/thinking turn.
 */
fun coalesceAcpTranscriptEvents(events: List<AgentEvent>): List<AgentEvent> =
    coalesceAgentStreamDeltas(emptyList(), recoverAcpWhitespaceChunks(events))

/**
 * Recover a plan-mode response from chat history when [plan.md] was never written.
 * Prefers provider completion text, then the last substantive assistant message.
 */
fun planTextFromAcpTranscript(events: List<AgentEvent>): String? {
    val display = coalesceAcpTranscriptEvents(events).filterNot { event ->
        event is AgentEvent.AvailableCommands || event is AgentEvent.AvailableModes || event is AgentEvent.Raw
    }
    display.filterIsInstance<AgentEvent.PlanUpdate>()
        .mapNotNull { it.markdown?.trim()?.takeIf(String::isNotBlank) }
        .lastOrNull()
        ?.let { return it }
    display.filterIsInstance<AgentEvent.PlanUpdate>()
        .mapNotNull { update ->
            update.entries.map { it.content.trim() }.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
        }
        .lastOrNull()
        ?.let { return it }
    display.filterIsInstance<AgentEvent.TaskResult>()
        .mapNotNull { it.finalText?.trim()?.takeIf(String::isNotBlank) }
        .lastOrNull()
        ?.let { return it }
    return display.filterIndexed { index, event ->
        val completion = display.getOrNull(index + 1) as? AgentEvent.TaskResult
        event !is AgentEvent.AssistantText || completion?.finalText?.trim() != event.text.trim()
    }
        .filterIsInstance<AgentEvent.AssistantText>()
        .map { it.text.trim() }
        .lastOrNull { it.isNotBlank() }
}

/**
 * Merge stream deltas into one live message. Transparent events such as whitespace-only ACP
 * chunks stored as [AgentEvent.Raw] must not split a provider response into separate bubbles.
 * Keep the original [AgentEvent.atMillis] so transcript LazyColumn keys stay stable while text
 * grows — rewriting the timestamp every token remounts the bubble.
 */
fun coalesceAgentStreamDeltas(
    existing: List<AgentEvent>,
    incoming: List<AgentEvent>,
): List<AgentEvent> = incoming.fold(existing) { transcript, event ->
    when {
        event is AgentEvent.AssistantText && event.isStreamDelta ->
            mergeStreamDelta(transcript, event)
        event is AgentEvent.Thinking && event.isStreamDelta ->
            mergeStreamDelta(transcript, event)
        else -> transcript + event
    }
}

private fun mergeStreamDelta(
    transcript: List<AgentEvent>,
    event: AgentEvent.AssistantText,
): List<AgentEvent> {
    val index = transcript.indexOfLast { it is AgentEvent.AssistantText && it.isStreamDelta }
    if (index < 0) return transcript + event
    if (transcript.subList(index + 1, transcript.size).any { it.isStreamCoalesceBarrier() }) {
        return transcript + event
    }
    val previous = transcript[index] as AgentEvent.AssistantText
    return transcript.toMutableList().also {
        it[index] = previous.copy(text = previous.text + event.text)
    }
}

private fun mergeStreamDelta(
    transcript: List<AgentEvent>,
    event: AgentEvent.Thinking,
): List<AgentEvent> {
    val index = transcript.indexOfLast { it is AgentEvent.Thinking && it.isStreamDelta }
    if (index < 0) return transcript + event
    if (transcript.subList(index + 1, transcript.size).any { it.isStreamCoalesceBarrier() }) {
        return transcript + event
    }
    val previous = transcript[index] as AgentEvent.Thinking
    return transcript.toMutableList().also {
        it[index] = previous.copy(text = previous.text + event.text)
    }
}

data class AgentCliStatus(
    val kind: AgentKind,
    val binaryPath: String? = null,
    val version: String? = null,
    /** Whether the ACP lane can launch independently of the vendor CLI binary. */
    val acpReady: Boolean = false,
    /** A setup issue detected before starting a task, phrased for the Agents UI. */
    val issue: AgentCliIssue? = null,
) {
    val available: Boolean get() = binaryPath != null
    /** Whether Andy can safely start a task with this CLI. */
    val ready: Boolean get() = (available || acpReady) && (issue?.blocksTasks != true || acpReady)
}

/**
 * An actionable local-installation problem. This deliberately holds user-facing
 * copy instead of leaking a provider process error into a task transcript.
 */
data class AgentCliIssue(
    val title: String,
    val detail: String,
    val repairCommand: String? = null,
    val blocksTasks: Boolean = false,
)
