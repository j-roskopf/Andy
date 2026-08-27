package app.andy.ui.shell

import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentReasoningEffort
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTask
import app.andy.model.AgentTaskDraft
import app.andy.model.LocalAgentRuntime
import app.andy.model.isLocalModelBackend

internal data class SideChatLaunchConfig(
    val agent: AgentKind,
    val localRuntime: LocalAgentRuntime? = null,
    val model: String? = null,
    val reasoningEffort: AgentReasoningEffort? = null,
    val sandboxMode: AgentSandboxMode = AgentSandboxMode.ReadOnly,
)

internal fun AgentSandboxMode.matchingAutonomy(): AgentAutonomy = when (this) {
    AgentSandboxMode.ReadOnly -> AgentAutonomy.ReadOnly
    AgentSandboxMode.WorkspaceWrite -> AgentAutonomy.Standard
    AgentSandboxMode.None -> AgentAutonomy.Full
}

internal fun sideChatAgent(parent: AgentKind, statuses: List<AgentCliStatus>): AgentKind {
    val ready = statuses.filter { it.ready }.map { it.kind }.distinct()
    return ready.firstOrNull { it != parent && !it.isLocalModelBackend }
        ?: ready.firstOrNull { it != parent }
        ?: ready.firstOrNull()
        ?: parent
}

internal fun sideChatPrompt(parent: AgentTask, question: String): String = buildString {
    appendLine("You are a side chat for Andy task ${parent.id} (\"${parent.title}\").")
    appendLine("The user is still working in that chat. Answer their question; do not take over the work.")
    appendLine()
    parent.goal?.takeIf { it.isNotBlank() }?.let { goal ->
        appendLine("Parent goal:")
        appendLine(goal.trim())
        appendLine()
    }
    appendLine("Parent agent: ${parent.agent.label}")
    parent.cwd?.let { appendLine("Working directory: $it") }
    parent.originDir?.takeIf { it != parent.cwd }?.let { appendLine("Project directory: $it") }
    appendLine()
    appendLine("Original prompt:")
    appendLine(parent.prompt.trim().sideChatExcerpt())
    parent.latestPrompt?.takeIf { it.isNotBlank() && it.trim() != parent.prompt.trim() }?.let { latest ->
        appendLine()
        appendLine("Latest user message:")
        appendLine(latest.trim().sideChatExcerpt())
    }
    parent.completedResultText?.takeIf { it.isNotBlank() }?.let { result ->
        appendLine()
        appendLine("Latest assistant result:")
        appendLine(result.trim().sideChatExcerpt())
    }
    appendLine()
    appendLine("The user's question:")
    appendLine(question.trim())
    appendLine()
    append("This is analysis only. Do NOT edit, create, or delete any files unless the user explicitly asks you to.")
}

internal fun sideChatDraft(
    parent: AgentTask,
    question: String,
    statuses: List<AgentCliStatus>,
    providerDefaults: Map<AgentKind, AgentProviderDefaults>,
    launch: SideChatLaunchConfig? = null,
): AgentTaskDraft {
    val agent = launch?.agent ?: sideChatAgent(parent.agent, statuses)
    val defaults = providerDefaults[agent]
    val sandbox = launch?.sandboxMode ?: AgentSandboxMode.ReadOnly
    val titleBase = parent.title.trim().ifBlank { parent.id }
    return AgentTaskDraft(
        title = "Side · $titleBase".take(60),
        prompt = sideChatPrompt(parent, question),
        agent = agent,
        localRuntime = launch?.localRuntime
            ?: defaults?.localRuntime
            ?: parent.localRuntime.takeIf { agent == parent.agent },
        projectId = parent.projectId,
        directory = parent.cwd ?: parent.originDir,
        useWorktree = false,
        attachAndyMcp = false,
        autonomy = sandbox.matchingAutonomy(),
        sandboxMode = sandbox,
        model = if (launch != null) launch.model else defaults?.model,
        reasoningEffort = if (launch != null) launch.reasoningEffort else defaults?.reasoningEffort,
        parentChatTaskId = parent.id,
    )
}

private fun String.sideChatExcerpt(max: Int = 2_000): String {
    val flat = trim()
    if (flat.length <= max) return flat
    return flat.take(max - 1) + "…"
}
