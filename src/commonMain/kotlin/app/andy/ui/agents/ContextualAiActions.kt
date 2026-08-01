package app.andy.ui.agents

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import app.andy.domain.redactHeaders
import app.andy.model.AgentAutonomy
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.AgentProviderDefaults
import app.andy.model.AgentSandboxMode
import app.andy.model.AgentTaskDraft
import app.andy.model.ContextualActionRequest
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationTimeline
import app.andy.model.attachesAndyMcpByDefault
import app.andy.model.taskTitle
import app.andy.service.AndyServices
import app.andy.service.BugService
import app.andy.service.InvestigationEvidenceService
import app.andy.service.UnavailableInvestigationEvidenceService
import app.andy.ui.components.OutlinedButton
import app.andy.ui.shell.LocalOpenAgentTask

/**
 * Shared plumbing for the contextual "Explain…" entry points (§5). Entry points build a
 * [ContextualActionRequest], hand it to a [ContextualAiActionState], and render
 * [AgentContextualActionSheet] — nothing is ever sent to a provider without the sheet's
 * explicit confirmation.
 */

/** False on platforms without a managed evidence root (web), where these actions stay hidden. */
internal fun contextualEvidenceAvailable(evidence: InvestigationEvidenceService): Boolean =
    evidence !== UnavailableInvestigationEvidenceService

/** Whether the "Explain…" affordances should be offered at all on this platform. */
internal fun contextualAiActionsEnabled(services: AndyServices): Boolean =
    contextualEvidenceAvailable(services.evidence) && services.capabilities.hostAutomation

/** Where a confirmed contextual action should go. */
internal sealed interface ContextualLaunchTarget {
    data object NewTask : ContextualLaunchTarget
    data class ExistingChat(val taskId: String) : ContextualLaunchTarget
}

/** Holds the single contextual action awaiting confirmation for one screen. */
@Stable
internal class ContextualAiActionState {
    var pending by mutableStateOf<ContextualActionRequest?>(null)
        private set

    fun open(request: ContextualActionRequest) {
        pending = request
    }

    fun dismiss() {
        pending = null
    }
}

@Composable
internal fun rememberContextualAiActionState(): ContextualAiActionState = remember { ContextualAiActionState() }

/** Renders the confirmation sheet for whichever action [state] currently holds, if any. */
@Composable
internal fun ContextualAiActionHost(services: AndyServices, state: ContextualAiActionState) {
    val openAgentTask = LocalOpenAgentTask.current
    state.pending?.let { request ->
        AgentContextualActionSheet(
            request = request,
            services = services,
            onDismiss = state::dismiss,
            onLaunched = openAgentTask,
        )
    }
}

/** The shared "Explain …" affordance every contextual entry point uses. */
@Composable
internal fun ExplainActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) { Text(label, fontSize = 11.sp) }
}

/**
 * Andy's default launch settings for a contextual action: a read-only, sandboxed chat in normal
 * (non-plan) mode with no worktree, reusing the provider's last-used model. MCP is attached only
 * for live-device actions so the agent can look at the device the question came from.
 */
internal fun contextualTaskDraft(
    request: ContextualActionRequest,
    prompt: String,
    agent: AgentKind,
    defaults: AgentProviderDefaults?,
    contextBundleIds: List<String>,
): AgentTaskDraft = AgentTaskDraft(
    title = request.taskTitle(),
    prompt = prompt,
    agent = agent,
    projectId = null,
    useWorktree = false,
    attachAndyMcp = request.kind.attachesAndyMcpByDefault(),
    autonomy = AgentAutonomy.ReadOnly,
    sandboxMode = AgentSandboxMode.ReadOnly,
    planMode = false,
    model = defaults?.model,
    reasoningEffort = defaults?.reasoningEffort,
    fastMode = defaults?.fastMode ?: false,
    openClawNewSession = defaults?.openClawNewSession ?: true,
    contextBundleIds = contextBundleIds,
    provenance = request.provenance,
)

/** The provider a contextual action launches with: last used when ready, else any ready CLI. */
internal fun contextualAgentKind(lastUsed: AgentKind?, statuses: List<AgentCliStatus>): AgentKind? =
    lastUsed?.takeIf { preferred -> statuses.any { it.kind == preferred && it.ready } }
        ?: statuses.firstOrNull { it.ready }?.kind

/** Where a live-surface object (crash, exchange, node) was recorded on a saved timeline. */
internal data class InvestigationEventLocation(
    val investigationId: String,
    val eventId: String,
    val atMillis: Long,
)

/** The first event whose correlation map carries [key] = [value]. */
internal fun InvestigationTimeline.eventByCorrelation(key: String, value: String): InvestigationEvent? =
    events.firstOrNull { it.correlationIds[key] == value }

/**
 * Looks through the most recent saved reports for the timeline event a live-surface object was
 * recorded as. Returns null when no saved investigation holds it, in which case the caller
 * falls back to a prompt-only action.
 */
internal suspend fun findInvestigationEvent(
    bugs: BugService,
    key: String,
    value: String,
    searchDepth: Int = 5,
): InvestigationEventLocation? {
    val reports = runCatching { bugs.listBugs() }.getOrNull().orEmpty()
        .sortedByDescending { it.capturedAtMillis }
        .take(searchDepth)
    reports.forEach { report ->
        val timeline = runCatching { bugs.loadBugTimeline(report.id) }.getOrNull() ?: return@forEach
        val event = timeline.eventByCorrelation(key, value)
        if (event != null) {
            return InvestigationEventLocation(report.id, event.id, event.atMillis)
        }
    }
    return null
}

/**
 * The newest saved event of [kind], for surfaces (the hierarchy inspector) whose live objects
 * carry no correlation id back onto a timeline.
 */
internal suspend fun findLatestInvestigationEvent(
    bugs: BugService,
    kind: InvestigationEventKind,
    searchDepth: Int = 5,
): InvestigationEventLocation? {
    val reports = runCatching { bugs.listBugs() }.getOrNull().orEmpty()
        .sortedByDescending { it.capturedAtMillis }
        .take(searchDepth)
    reports.forEach { report ->
        val timeline = runCatching { bugs.loadBugTimeline(report.id) }.getOrNull() ?: return@forEach
        val event = timeline.events.lastOrNull { it.kind == kind }
        if (event != null) {
            return InvestigationEventLocation(report.id, event.id, event.atMillis)
        }
    }
    return null
}

/** A compact, redacted header line for prompt-only network actions (no bundle to point at). */
internal fun redactedHeaderSummary(
    requestHeaders: Map<String, String>,
    responseHeaders: Map<String, String>,
    maxHeaders: Int = 8,
): String? {
    fun summarize(label: String, headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        val redacted = redactHeaders(headers).headers
        val shown = redacted.entries.take(maxHeaders)
            .joinToString(", ") { (name, value) -> "$name=$value" }
        val remaining = (redacted.size - maxHeaders).coerceAtLeast(0)
        return if (remaining > 0) "$label $shown (+$remaining more)" else "$label $shown"
    }
    return listOfNotNull(
        summarize("request:", requestHeaders),
        summarize("response:", responseHeaders),
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
