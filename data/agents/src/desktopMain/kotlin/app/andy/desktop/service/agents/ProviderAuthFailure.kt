package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.looksLikeProviderAuthFailure
import app.andy.model.providerAuthFailureHint

/**
 * Prefer a login hint when the provider reported an auth failure; otherwise keep
 * a readable ACP phase message (and avoid dropping the underlying detail).
 */
internal fun friendlyAcpFailureMessage(
    agent: AgentKind,
    phase: AcpFailurePhase,
    raw: String?,
): String {
    providerAuthFailureHint(agent, raw)?.let { return it }
    val detail = raw?.trim()?.takeIf { it.isNotBlank() }
    return when (phase) {
        AcpFailurePhase.Start -> detail?.let { "ACP failed to start: $it" } ?: "ACP failed to start"
        AcpFailurePhase.Resume -> detail?.let { "ACP failed to resume: $it" } ?: "ACP failed to resume"
        AcpFailurePhase.Prompt -> detail ?: "ACP prompt failed"
    }
}

internal enum class AcpFailurePhase {
    Start,
    Resume,
    Prompt,
}

/** Re-export for desktop call sites / tests that still import from this package. */
internal fun looksLikeProviderAuthFailure(raw: String?): Boolean =
    app.andy.model.looksLikeProviderAuthFailure(raw)

internal fun providerAuthFailureHint(agent: AgentKind, raw: String?): String? =
    app.andy.model.providerAuthFailureHint(agent, raw)
