package app.andy.model

/**
 * Detects provider login / OAuth failures so Andy can offer host-side recovery
 * instead of only a raw transport error.
 *
 * Provider OAuth (browser + interactive CLI) must finish on the Andy host.
 * Remote clients can only open a host terminal and/or show a copyable command.
 */
fun looksLikeProviderAuthFailure(raw: String?): Boolean {
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

/** Shell command users copy / run on the Andy host to start provider sign-in. */
fun providerLoginCommand(agent: AgentKind): String = when (agent) {
    AgentKind.ClaudeCode -> "claude"
    AgentKind.Codex -> "codex login"
    else -> agent.cliName
}

/**
 * Command actually launched in the host Terminal.app window.
 * May include a one-line reminder before the interactive CLI (Claude cannot
 * complete `/login` non-interactively).
 */
fun providerLoginTerminalCommand(agent: AgentKind): String = when (agent) {
    AgentKind.ClaudeCode ->
        "printf '%s\\n' '' 'Claude needs an interactive login on this Mac.' " +
            "'When the prompt appears, type /login and finish browser OAuth, then retry in Andy.' '' && claude"
    else -> providerLoginCommand(agent)
}

/** Short steps for clients that are already on the Andy host (desktop / local CLI). */
fun providerLoginInstructions(agent: AgentKind): String = when (agent) {
    AgentKind.ClaudeCode ->
        "On this Mac: Terminal will open Claude — type /login if prompted, finish browser OAuth, then retry."
    else ->
        "On this Mac: finish sign-in in the terminal / browser, then retry."
}

/**
 * Copy for remote Network Access clients (Android / web / remote CLI).
 * OAuth cannot complete on the phone or in the browser SPA.
 */
fun providerLoginRemoteInstructions(agent: AgentKind): String {
    val command = providerLoginCommand(agent)
    return when (agent) {
        AgentKind.ClaudeCode ->
            "This phone can’t finish Claude OAuth. On your Mac run `$command`, type /login if prompted, complete the browser sign-in, then retry here."
        else ->
            "This client can’t finish provider OAuth. On your Mac run `$command`, complete sign-in, then retry here."
    }
}

/** Actionable recovery copy when [raw] looks like a missing/expired provider login. */
fun providerAuthFailureHint(agent: AgentKind, raw: String?): String? {
    if (!looksLikeProviderAuthFailure(raw)) return null
    val command = providerLoginCommand(agent)
    return when (agent) {
        AgentKind.ClaudeCode ->
            "Not logged in — sign in on the Andy host (`$command`, then /login), then retry"
        else ->
            "Not logged in — sign in on the Andy host (`$command`), then retry"
    }
}

/** Human message after a successful host Terminal open (all clients). */
fun providerLoginOpenedMessage(agent: AgentKind): String = when (agent) {
    AgentKind.ClaudeCode ->
        "Opened Terminal on your Mac. Switch to that computer, type /login if prompted, finish browser OAuth, then retry here."
    else ->
        "Opened Terminal on your Mac. Switch to that computer, finish sign-in, then retry here."
}

/**
 * Structured recovery payload for chat JSON / UI when [AgentTask.errorMessage]
 * indicates a provider auth failure.
 */
data class ProviderAuthRecovery(
    val agent: AgentKind,
    val command: String,
    /** Host-local instructions (desktop). */
    val instructions: String,
    /** Remote-client instructions (Android / web). */
    val remoteInstructions: String,
)

fun AgentTask.providerAuthRecoveryOrNull(): ProviderAuthRecovery? {
    if (!looksLikeProviderAuthFailure(errorMessage)) return null
    return ProviderAuthRecovery(
        agent = agent,
        command = providerLoginCommand(agent),
        instructions = providerLoginInstructions(agent),
        remoteInstructions = providerLoginRemoteInstructions(agent),
    )
}
