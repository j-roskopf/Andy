package app.andy.terminal

import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.getPlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
import app.andy.model.TerminalAppearanceSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    /** Settles the replay frame gate / paint kicks; cancelled by [disposeScrollbackReplayView]. */
    internal var replaySettleJob: Job? = null

    /** Set when [createScrollbackReplayView] finishes parsing; null for live sessions. */
    internal var replaySettled: AtomicBoolean? = null

    /** Read-only replay is hidden until parsing settles so history does not visibly redraw. */
    fun isScrollbackReplayReady(): Boolean = !readOnly || replaySettled?.get() == true
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
    val replayRows = rows.coerceAtLeast(1)
    val payload = (display.replace("\r\n", "\n").replace("\n", "\r\n") + "\u001b[0m\u001b[?25l")
    val state = EmbeddableTerminalState()
    val settings = appearance.toBossTermSettings(
        scrollbackLines = BossTermBackend.DEFAULT_MAX_HISTORY,
        agentCliMode = true,
    )
    // Hold the one-shot feed until the emulator is resized to [columns]. Feeding at BossTerm's
    // default grid hard-wraps every boxed TUI line and is what makes history look shattered.
    val feedGate = CompletableDeferred<Unit>()
    val payloadDelivered = AtomicBoolean(false)
    val replaySettled = AtomicBoolean(false)
    val services = ReplayPlatformServices(
        payload = payload,
        feedGate = feedGate,
        onPayloadDelivered = { payloadDelivered.set(true) },
    )
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
    // initializeSession spawns on a BossTerm coroutine; wait briefly so resize/display exist.
    awaitReplayTab(state, timeoutMs = 5_000)
    BossTermAccess.resizeTerminal(state, columns, replayRows)
    feedGate.complete(Unit)

    val settleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        view.replaySettled = replaySettled
        // The whole transcript is replayed as one payload and parsed character by character,
        // so an ungated replay asks for thousands of full-grid renders to reach a screen that
        // never changes again. Cap it while feeding, then leave the gate open and force a paint —
        // cursor is hidden for replay, so caret-blink cannot recover a stuck blank frame.
        val termDisplay = BossTermAccess.display(state)
        if (termDisplay != null) {
            val limiter = TerminalFrameLimiter(
                display = termDisplay,
                churningProvider = { !replaySettled.get() },
            ).also { it.start() }
            view.frameLimiter = limiter
            view.replaySettleJob = settleScope.launch {
                settleScrollbackReplayPaint(
                    state = state,
                    payloadDelivered = payloadDelivered,
                    replaySettled = replaySettled,
                    limiter = limiter,
                    payloadChars = payload.length,
                )
            }.also { job ->
                job.invokeOnCompletion { settleScope.cancel() }
            }
        } else {
            settleScope.cancel()
        }
    }
}

fun disposeScrollbackReplayView(view: AndyTerminalView) {
    runCatching { view.replaySettleJob?.cancel() }
    view.replaySettleJob = null
    runCatching { view.frameLimiter?.close() }
    view.frameLimiter = null
    runCatching { view.state.dispose() }
}

/**
 * Kick the history surface until a committed frame can capture real buffer content.
 * Safe to call repeatedly; no-op when the session has already been disposed.
 */
internal fun kickScrollbackReplayPaint(view: AndyTerminalView) {
    if (view.state.isDisposed) return
    view.frameLimiter?.flushNow()
    BossTermAccess.requestRedraw(view.state)
}

private suspend fun settleScrollbackReplayPaint(
    state: EmbeddableTerminalState,
    payloadDelivered: AtomicBoolean,
    replaySettled: AtomicBoolean,
    limiter: TerminalFrameLimiter,
    payloadChars: Int,
) {
    val deliverDeadline = System.currentTimeMillis() + 10_000L
    while (!payloadDelivered.get() && System.currentTimeMillis() < deliverDeadline) {
        if (!coroutineContext.isActive) return
        delay(10)
    }
    // Emulation of the one-shot chunk is async; budget scales with transcript size.
    val parseBudgetMs = ((payloadChars / 40_000L) * 100L + 150L).coerceIn(150L, 8_000L)
    var last = ""
    var stableHits = 0
    val parseDeadline = System.currentTimeMillis() + parseBudgetMs
    while (System.currentTimeMillis() < parseDeadline) {
        if (!coroutineContext.isActive) return
        delay(40)
        val text = BossTermAccess.screenText(state)
        if (text.isNotBlank() && text == last) {
            stableHits++
            if (stableHits >= 2) break
        } else {
            stableHits = 0
            last = text
        }
    }
    replaySettled.set(true)
    limiter.flushNow()
    BossTermAccess.requestRedraw(state)
    // Compose may mount after settle; a few ungated kicks cover the blank-until-resize race.
    repeat(4) {
        if (!coroutineContext.isActive) return
        delay(120)
        limiter.flushNow()
        BossTermAccess.requestRedraw(state)
    }
}

private fun awaitReplayTab(state: EmbeddableTerminalState, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (BossTermAccess.tab(state) == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(25)
    }
}
