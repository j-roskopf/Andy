package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask

internal data class ProviderDesktopContinuation(
    val providerLabel: String,
    val uri: String,
)

/**
 * Returns a handoff only when the provider documents a deep link for this exact session.
 * Opening a provider at the task's folder is intentionally not treated as continuation.
 */
internal fun AgentTask.providerDesktopContinuation(macOs: Boolean): ProviderDesktopContinuation? {
    if (!macOs || agent != AgentKind.Codex) return null
    val sessionId = vendorSessionId?.takeIf { it.isNotBlank() }
        ?: acpSessionId?.takeIf { it.isNotBlank() }
        ?: return null
    return ProviderDesktopContinuation(
        providerLabel = agent.label,
        uri = "codex://threads/$sessionId",
    )
}
