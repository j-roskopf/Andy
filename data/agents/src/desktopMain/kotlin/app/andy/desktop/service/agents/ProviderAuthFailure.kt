package app.andy.desktop.service.agents

import app.andy.model.AgentKind

/**
 * Detects provider login / OAuth failures so Andy can show a clear recovery
 * hint instead of a raw ACP transport error.
 */
internal fun looksLikeProviderAuthFailure(raw: String?): Boolean {
    val text = raw?.lowercase()?.trim().orEmpty()
    if (text.isEmpty()) return false
    return "/login" in text ||
        "not logged in" in text ||
        "please log in" in text ||
        "please login" in text ||
        "please run /login" in text ||
        "failed to authenticate" in text ||
        "oauth session expired" in text ||
        "could not be refreshed" in text ||
        "authentication failed" in text ||
        "authentication required" in text ||
        "auth required" in text ||
        (text.contains("unauthorized") && (text.contains("auth") || text.contains("login") || text.contains("oauth")))
}

/** Actionable recovery copy when [raw] looks like a missing/expired provider login. */
internal fun providerAuthFailureHint(agent: AgentKind, raw: String?): String? {
    if (!looksLikeProviderAuthFailure(raw)) return null
    return when (agent) {
        AgentKind.ClaudeCode ->
            "Not logged in — run `claude` in a terminal and sign in (`/login`), then retry"
        else ->
            "Not logged in — run `${agent.cliName}` in a terminal and sign in, then retry"
    }
}

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
