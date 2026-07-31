package app.andy.terminal

import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import app.andy.model.TerminalAppearanceSnapshot

/**
 * Opaque handle the Compose UI mounts via [EmbeddableTerminal].
 * Replaces the former KetraTerm [SwingTerminal] widget pointer.
 */
data class AndyTerminalView(
    val state: EmbeddableTerminalState,
    val settingsOverride: TerminalSettingsOverride,
    val platformServices: PlatformServices = getPlatformServices(),
    val command: String = "/bin/sh",
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    /** Session-owned native history index; null for read-only replay views. */
    val history: TerminalHistoryController? = null,
    /** When true, the view is read-only history replay (no live PTY ownership). */
    val readOnly: Boolean = false,
    /** Work around BossTerm wheel encoding by writing SGR mouse reports to an attached tmux client. */
    val tmuxScrollback: Boolean = false,
) {
    /**
     * Frame cap for replay views only — live sessions have theirs owned by [BossTermBackend].
     * Deliberately outside the constructor so it stays out of equals/hashCode: it is lifecycle
     * state, not view identity.
     */
    internal var frameLimiter: TerminalFrameLimiter? = null
}

fun BossTermBackend.toTerminalView(): AndyTerminalView = AndyTerminalView(
    state = terminalViewState(),
    settingsOverride = settingsOverride(),
    platformServices = platformServices(),
    command = embedCommand(),
    workingDirectory = embedWorkingDirectory(),
    environment = embedEnvironment(),
    history = terminalHistory(),
    readOnly = false,
    tmuxScrollback = forwardsMouseToApplication(),
)

fun createScrollbackReplayView(
    content: String,
    cols: Int = 0,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
): AndyTerminalView {
    val display = content.trimEnd().ifBlank { "(no readable history for this chat)" }
    val columns = if (cols > 0) cols else scrollbackReplayColumns(display)
    val payload = (display.replace("\r\n", "\n").replace("\n", "\r\n") + "\u001b[0m\u001b[?25l")
    val state = EmbeddableTerminalState()
    val settings = appearance.toBossTermSettings(
        scrollbackLines = BossTermBackend.DEFAULT_MAX_HISTORY,
        agentCliMode = true,
    )
    val services = ReplayPlatformServices(payload)
    BossTermAccess.initialize(
        state = state,
        settings = settings,
        command = "/bin/sh",
        workingDirectory = resolveTerminalWorkingDirectory(null),
        environment = emptyMap(),
        onOutput = null,
        onExit = null,
        platformServices = services,
    )
    return AndyTerminalView(
        state = state,
        settingsOverride = appearance.toBossTermSettingsOverride(
            scrollbackLines = BossTermBackend.DEFAULT_MAX_HISTORY,
            agentCliMode = true,
        ).copy(
            showScrollbar = true,
            enableMouseReporting = false,
        ),
        platformServices = services,
        command = "/bin/sh",
        readOnly = true,
    ).also { view ->
        // The whole transcript is replayed as one payload and parsed character by character,
        // so an ungated replay asks for thousands of full-grid renders to reach a screen that
        // never changes again. Cap it like a live session.
        BossTermAccess.display(state)?.let { display ->
            view.frameLimiter = TerminalFrameLimiter(display).also { it.start() }
        }
    }
}

fun disposeScrollbackReplayView(view: AndyTerminalView) {
    runCatching { view.frameLimiter?.close() }
    view.frameLimiter = null
    runCatching { view.state.dispose() }
}
