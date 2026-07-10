package app.andy.model

enum class AgentKind(val label: String, val cliName: String) {
    ClaudeCode("Claude Code", "claude"),
    Codex("Codex", "codex"),
    Cursor("Cursor", "cursor-agent"),
    Antigravity("Antigravity", "agy"),
}

/** Unified autonomy dial; each adapter maps it to vendor-specific flags. */
enum class AgentAutonomy(val label: String) {
    ReadOnly("read-only"),
    Standard("standard"),
    Full("full"),
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
)

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
            AgentModelOption("Auto", "Auto", emptyList()),
            AgentModelOption("Composer 2.5", "Composer 2.5", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh), supportsFastMode = true),
            AgentModelOption("Opus 4.8", "Opus 4.8", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh), supportsFastMode = true),
            AgentModelOption("GPT-5.6 Sol", "GPT-5.6 Sol", listOf(AgentReasoningEffort.Medium, AgentReasoningEffort.High, AgentReasoningEffort.ExtraHigh), supportsFastMode = true),
            AgentModelOption("Gemini 3.1 Pro", "Gemini 3.1 Pro", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High), supportsFastMode = true),
            AgentModelOption("Grok 4.5", "Grok 4.5", listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High), supportsFastMode = true),
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
        id?.let { modelId -> options(agent).firstOrNull { it.id == modelId } }
}

enum class AgentTaskStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Stopped,

    /** Was Running or Queued when the app restarted; the process is gone. */
    Unknown,
}

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
    val maxBudgetUsd: Double? = null,
    /** Files already changed when the task began; excluded from its change summary. */
    val changeBaselinePaths: List<String> = emptyList(),
    val hasChangeBaseline: Boolean = false,
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
) {
    val isActive: Boolean get() = status == AgentTaskStatus.Queued || status == AgentTaskStatus.Running
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
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val imagePaths: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
    val maxBudgetUsd: Double? = null,
)

/** Last-used launch settings, stored independently for each provider. */
data class AgentProviderDefaults(
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val fastMode: Boolean = false,
    val autonomy: AgentAutonomy = AgentAutonomy.Standard,
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

data class AgentFileChange(
    val path: String,
    val additions: Int,
    val deletions: Int,
)

data class AgentChangeSummary(val files: List<AgentFileChange>) {
    val additions: Int get() = files.sumOf { it.additions }
    val deletions: Int get() = files.sumOf { it.deletions }
}

fun AgentTaskDraft.providerDefaults(): AgentProviderDefaults = AgentProviderDefaults(
    model = model,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
    autonomy = autonomy,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)

fun AgentTask.providerDefaults(): AgentProviderDefaults = AgentProviderDefaults(
    model = model,
    reasoningEffort = reasoningEffort,
    fastMode = fastMode,
    autonomy = autonomy,
    useWorktree = useWorktree,
    attachAndyMcp = attachAndyMcp,
    maxBudgetUsd = maxBudgetUsd,
)

/** Provider-specific model string passed by the adapter. */
fun AgentTask.modelForCli(): String? = model?.let { selected ->
    when (agent) {
        AgentKind.Cursor -> buildString {
            append(selected)
            reasoningEffort?.let { append(' ').append(it.label.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }) }
            if (fastMode) append(" Fast")
        }
        AgentKind.Antigravity -> reasoningEffort?.let { "$selected (${it.label.split(' ').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }})" } ?: selected
        else -> selected
    }
}

fun AgentTask.modelConfigurationLabel(): String = buildList {
    model?.let(::add) ?: add("provider default")
    reasoningEffort?.let { add(it.label) }
    if (fastMode) add("fast")
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

fun AgentTask.promptForCli(): String = promptWithImageHints(promptWithSkillHints(prompt, skills), imagePaths)

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
            model == "Auto" -> TokenPrice(inputUsdPerMillion = 1.25, outputUsdPerMillion = 6.0)
            model?.contains("Opus", ignoreCase = true) == true -> TokenPrice(inputUsdPerMillion = 5.0, outputUsdPerMillion = 25.0)
            model?.contains("GPT", ignoreCase = true) == true -> TokenPrice(inputUsdPerMillion = 1.25, outputUsdPerMillion = 10.0)
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
    ) : AgentEvent
    data class ToolCall(override val atMillis: Long, val toolName: String, val summary: String, val detail: String = summary) : AgentEvent
    data class ToolResult(override val atMillis: Long, val toolName: String?, val summary: String, val detail: String = summary, val isError: Boolean) : AgentEvent
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

    /** Fallback for stdout lines the adapter could not parse; nothing is dropped. */
    data class Raw(override val atMillis: Long, val line: String) : AgentEvent
}

data class AgentCliStatus(
    val kind: AgentKind,
    val binaryPath: String? = null,
    val version: String? = null,
) {
    val available: Boolean get() = binaryPath != null
}
