package app.andy.model

enum class AgentKind(val label: String, val cliName: String) {
    ClaudeCode("Claude Code", "claude"),
    Codex("Codex", "codex"),
    Cursor("Cursor", "cursor-agent"),
    Antigravity("Antigravity", "agy"),
}

/** Command for installing the portable grill-me skill into this provider's global skill directory. */
fun AgentKind.grillMeInstallCommand(): String =
    "npx skills add mattpocock/skills --skill grill-me --global --agent ${grillMeSkillsAgent()}"

private fun AgentKind.grillMeSkillsAgent(): String = when (this) {
    AgentKind.ClaudeCode -> "claude-code"
    AgentKind.Codex -> "codex"
    AgentKind.Cursor -> "cursor"
    AgentKind.Antigravity -> "antigravity-cli"
}

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
    AgentKind.ClaudeCode, AgentKind.Antigravity -> "permissions"
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
}

/**
 * The effort requested from a provider. Not every provider supports every value;
 * [AgentModelCatalog] exposes only the combinations documented for its CLI.
 */
enum class AgentReasoningEffort(val label: String, val cliValue: String) {
    Low("low", "low"),
    Medium("medium", "medium"),
    High("high", "high"),
    ExtraHigh("extra high", "xhigh"),
    Max("max", "max"),
    Ultracode("ultracode", "ultracode"),
}

data class AgentModelOption(
    /** Model identifier passed to the provider CLI, before any provider-specific variant syntax. */
    val id: String,
    val label: String,
    val efforts: List<AgentReasoningEffort>,
    val supportsFastMode: Boolean = false,
) {
    /** Preferred effort when the CLI requires a suffix and the user left effort unset. */
    fun preferredEffort(): AgentReasoningEffort? =
        efforts.firstOrNull { it == AgentReasoningEffort.High } ?: efforts.firstOrNull()
}

/**
 * A small public catalog for the four CLIs, plus an exact custom-model escape hatch
 * in the composer. Account/workspace entitlements remain the source of truth.
 */
object AgentModelCatalog {
    fun options(agent: AgentKind): List<AgentModelOption> = when (agent) {
        AgentKind.Codex -> listOf(
            AgentModelOption("gpt-5.6-sol", "GPT-5.6 Sol", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh, AgentReasoningEffort.Max)),
            AgentModelOption("gpt-5.6-terra", "GPT-5.6 Terra", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("gpt-5.6-luna", "GPT-5.6 Luna", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("gpt-5.2-codex", "GPT-5.2-Codex", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh)),
        )
        AgentKind.ClaudeCode -> listOf(
            AgentModelOption("opus", "Opus", AgentReasoningEffort.entries.toList()),
            AgentModelOption("sonnet", "Sonnet", AgentReasoningEffort.entries.toList()),
            AgentModelOption("haiku", "Haiku", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("fable", "Fable", AgentReasoningEffort.entries.toList()),
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
            AgentModelOption("Gemini 3.5 Flash", "Gemini 3.5 Flash", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High)),
            AgentModelOption("Gemini 3.1 Pro", "Gemini 3.1 Pro", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.High)),
            AgentModelOption("Claude Sonnet 4.6", "Claude Sonnet 4.6", listOf(AgentReasoningEffort.High)),
            AgentModelOption("Claude Opus 4.6", "Claude Opus 4.6", listOf(AgentReasoningEffort.High)),
            AgentModelOption("GPT-OSS 120B", "GPT-OSS 120B", listOf(AgentReasoningEffort.Medium)),
        )
    }

    fun option(agent: AgentKind, id: String?): AgentModelOption? =
        id?.let { modelId ->
            val normalized = if (agent == AgentKind.Cursor) cursorModelBaseId(modelId) else modelId
            options(agent).firstOrNull { it.id == normalized }
        }
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
    else -> selected
}

enum class AgentTaskStatus {
    Queued,
    Running,
    /** The agent stopped at an explicit decision point and is awaiting a response in Andy. */
    WaitingForInput,
    Completed,
    Failed,
    Stopped,

    /** Was Running or Queued when the app restarted; the process is gone. */
    Unknown,
}

/** A follow-up held until the agent's current response completes. */
data class AgentQueuedFollowUp(
    val text: String,
    val imagePaths: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
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

/** A persisted decision checkpoint emitted by a provider-neutral agent protocol. */
data class AgentUserInputRequest(
    val id: String,
    val questions: List<AgentUserInputQuestion>,
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
    /** Final response from a successful plan-mode run, retained for a fresh implementation handoff. */
    val completedPlanText: String? = null,
    /** Generated context used only when a completed plan starts its fresh implementation run. */
    val implementationPrompt: String? = null,
    /** A fresh-provider continuation used only when that provider cannot resume its prior session. */
    val continuationPrompt: String? = null,
    /** Null keeps the provider's own default model. */
    val model: String? = null,
    /** Null keeps the provider's own default reasoning level. */
    val reasoningEffort: AgentReasoningEffort? = null,
    /** Cursor-only: request the provider's Fast variant when it has one. */
    val fastMode: Boolean = false,
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
    /** Files already changed when the task began; excluded from its change summary. */
    val changeBaselinePaths: List<String> = emptyList(),
    val hasChangeBaseline: Boolean = false,
    /** Immutable repository changes captured when this chat last finished. */
    val completedChanges: AgentThreadChangeSnapshot? = null,
    val status: AgentTaskStatus = AgentTaskStatus.Queued,
    val vendorSessionId: String? = null,
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
    /** True only for the run that created and may remove [worktreePath]. */
    val ownsWorktree: Boolean = false,
    /** Optional typed project task that launched this raw agent session. */
    val workflowTaskId: String? = null,
    val workflowStage: ProjectWorkflowStage? = null,
    val workflowAttempt: Int? = null,
    /** Final provider response for non-plan workflow stages and completed chats. */
    val completedResultText: String? = null,
) {
    val isActive: Boolean get() = status == AgentTaskStatus.Queued || status == AgentTaskStatus.Running
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
    }

    fun withAccess(agent: AgentKind, enabled: Boolean): AgentQuotaAccess = when (agent) {
        AgentKind.Codex -> this
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
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val imagePaths: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
    val goal: String? = null,
    val maxBudgetUsd: Double? = null,
    /** Reuse an existing workflow worktree instead of creating a new one. */
    val existingWorktreePath: String? = null,
    val existingBranchName: String? = null,
    val workflowTaskId: String? = null,
    val workflowStage: ProjectWorkflowStage? = null,
    val workflowAttempt: Int? = null,
)

/** Last-used launch settings, stored independently for each provider. */
data class AgentProviderDefaults(
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val autonomy: AgentAutonomy = AgentAutonomy.Standard,
    val sandboxMode: AgentSandboxMode? = null,
    val planMode: Boolean = false,
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
    autonomy = autonomy,
    sandboxMode = sandboxMode,
    planMode = planMode,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)

fun AgentTask.providerDefaults(): AgentProviderDefaults = AgentProviderDefaults(
    model = model,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
    autonomy = autonomy,
    sandboxMode = sandboxMode,
    planMode = planMode,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)

/** Provider-specific model string passed by the adapter. */
fun AgentTask.modelForCli(): String? = model?.let { selected ->
    when (agent) {
        AgentKind.Cursor -> {
            val base = cursorModelBaseId(selected)
            val catalog = AgentModelCatalog.option(AgentKind.Cursor, base)
            buildString {
                append(base)
                // Cursor variants bake effort into the model id; bare bases like cursor-grok-4.5 are rejected.
                val effort = reasoningEffort ?: catalog?.preferredEffort()
                if (effort != null && catalog?.efforts?.isNotEmpty() == true) {
                    append('-').append(effort.cliValue)
                }
                // Catalog options gate -fast; custom exact slugs stay unadorned.
                if (fastMode && catalog?.supportsFastMode == true) append("-fast")
            }
        }
        AgentKind.Antigravity -> reasoningEffort?.let { "$selected (${it.label.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }})" } ?: selected
        else -> selected
    }
}

fun AgentTask.modelConfigurationLabel(): String = buildList {
    model?.let(::add) ?: add("provider default")
    reasoningEffort?.let { add(it.label) }
    if (fastMode) add("fast")
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

private fun promptWithPlanModeHint(text: String, planMode: Boolean): String = if (planMode) {
    "$text\n\nPlan mode is active. Inspect and analyze the task, then return a concrete implementation plan. Do not edit files, apply patches, or run commands that modify the workspace."
} else {
    text
}

fun AgentTask.promptForCli(): String = promptWithImageHints(
    promptWithGoalHint(promptWithPlanModeHint(promptWithSkillHints(continuationPrompt ?: implementationPrompt ?: prompt, skills), planMode), goal),
    imagePaths,
)

fun AgentTask.followUpPromptForCli(text: String, imagePaths: List<String>): String = promptWithImageHints(
    promptWithPlanModeHint(text, planMode),
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
        // Claude Code reports its billed total; Antigravity currently reports no token usage.
        AgentKind.ClaudeCode, AgentKind.Antigravity -> null
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
    data class ToolCall(override val atMillis: Long, val toolName: String, val summary: String, val detail: String = summary) : AgentEvent
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

    /** Fallback for stdout lines the adapter could not parse; nothing is dropped. */
    data class Raw(override val atMillis: Long, val line: String) : AgentEvent
}

data class AgentCliStatus(
    val kind: AgentKind,
    val binaryPath: String? = null,
    val version: String? = null,
    /** A setup issue detected before starting a task, phrased for the Agents UI. */
    val issue: AgentCliIssue? = null,
) {
    val available: Boolean get() = binaryPath != null
    /** Whether Andy can safely start a task with this CLI. */
    val ready: Boolean get() = available && issue?.blocksTasks != true
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
