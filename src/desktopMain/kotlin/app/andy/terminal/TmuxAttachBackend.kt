package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import io.github.ketraterm.ui.swing.api.SwingTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GUI viewer: attaches KetraTerm/Pty4J to an existing `tmux -L andy` session.
 *
 * The agent process is owned by the detached tmux session (created by
 * [TmuxAndy.newSession] or [TmuxAgentBackend]); this backend provides the Swing
 * widget, the local keystroke path, and the screen that status detection reads.
 *
 * ### Where the screen comes from
 *
 * While a viewer is attached, buffer/title/liveness all come from the KetraTerm
 * emulator — it already parses every byte tmux sends the client, so its screen is the
 * same information `capture-pane` returned, for free. Polling tmux for it meant a
 * `ProcessBuilder.start()` per sample per session, and fork from a JVM this size is far
 * more expensive than the tmux command it runs: it takes libmalloc's fork lock, stalling
 * allocation on every Compose thread. Emulator parsing runs on KetraTerm's own render
 * worker, not AWT, so a throttled UI thread does not stall detection.
 *
 * tmux is still polled when no viewer is mounted (chat released to the background),
 * where there is no emulator to read — at [TMUX_BACKGROUND_SCRAPE_MS] cadence.
 */
class TmuxAttachBackend(
    override val sessionId: String,
    private val cols: Int = 120,
    private val rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
    private val killTmuxOnClose: Boolean = false,
) : TerminalSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var foreground: AtomicBoolean = AtomicBoolean(true)
    private var inner: KetraTermBackend = newInner(appearance)

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    private val _windowTitle = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    /** Forwards the current [inner]'s screen/title; rebound on every viewer (re)attach. */
    private var viewerJob: Job? = null

    /** Long-lived liveness watch; also the tmux fallback scrape when no viewer is mounted. */
    private var livenessJob: Job? = null

    @Volatile private var lastSnapshot: String = ""

    /**
     * A fresh KetraTerm viewer starts with an empty scrollback while tmux still holds what
     * scrolled past. Set on every attach so the next transcript capture bridges that gap
     * from tmux once, rather than every flush.
     */
    private val historyBridgePending = AtomicBoolean(true)

    /**
     * Last tmux liveness seen, and when. [isAlive] is read on UI and status-poll paths, so
     * answering it with a fork per call was a large share of Andy's CPU. A live viewer PTY
     * already proves the session is up (the attach client exits with it), so that answers
     * most calls; otherwise serve the watch loop's reading and only fork when it is stale.
     */
    @Volatile private var lastAliveSeen: Boolean = true

    @Volatile private var lastAliveAtMs: Long = 0L

    override val isAlive: Boolean
        get() {
            if (inner.isAlive) return true
            val age = System.currentTimeMillis() - lastAliveAtMs
            val fresh = livenessJob?.isActive == true && age <= LIVENESS_CACHE_MS
            if (fresh) return lastAliveSeen
            return TmuxAndy.hasSession(sessionId).also { markAlive(it) }
        }

    private fun markAlive(alive: Boolean) {
        lastAliveSeen = alive
        lastAliveAtMs = System.currentTimeMillis()
    }

    /** True while the local Swing/PTY viewer process attached to tmux is still running. */
    val isViewerAlive: Boolean
        get() = inner.isAlive

    override val pid: Long? get() = inner.pid
    override val oscProgress: StateFlow<String> get() = inner.oscProgress

    fun swingTerminal(): SwingTerminal? = inner.swingTerminal()

    fun scrollbackAnsi(): String = inner.scrollbackAnsi()

    fun captureReadableLines(seenKeys: MutableSet<String>): List<String> =
        inner.captureReadableLines(seenKeys)

    /**
     * Styled rows from the local viewer. Equivalent to `capture-pane -e` for anything the
     * viewer has been attached for — the Andy tmux server runs with `status off`, so the
     * client's screen is the pane and nothing else. Rows from before this viewer attached
     * still need tmux; see [consumeHistoryBridge].
     */
    fun captureStyledRows(maxRows: Int = KetraTermBackend.SCROLLBACK_CAPTURE_ROWS): List<StyledTerminalRow> =
        inner.captureStyledRows(maxRows)

    /**
     * True once per viewer attach, for callers that persist the transcript: the first
     * capture after attaching must come from tmux to pick up output produced while Andy
     * had no viewer. Every later capture can read the viewer.
     */
    fun consumeHistoryBridge(): Boolean = historyBridgePending.getAndSet(false)

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) = inner.updateAppearance(appearance)

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        // argv/cwd/env from callers are ignored — we always attach to the Andy tmux session.
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before attaching"
        }
        // The server may have been started by andyd or an older build; `status off` has to
        // hold before the client paints, or the scrape reads a status bar as the last row.
        TmuxAndy.ensureServerConfigured()
        val attachCwd = resolveTerminalWorkingDirectory(cwd)
        inner.start(TmuxAndy.attachArgv(sessionId), cwd = attachCwd, env = emptyMap())
        observeViewer(inner)
        ensureLivenessWatch()
    }

    /** Attach to an already-running tmux session (preferred entry for GUI). */
    fun attach() {
        start(emptyList(), cwd = resolveTerminalWorkingDirectory(null), env = emptyMap())
    }

    override fun write(bytes: ByteArray) = inner.write(bytes)

    override fun writeText(text: String) = inner.writeText(text)

    override fun resize(cols: Int, rows: Int) = inner.resize(cols, rows)

    override fun bufferSnapshot(): String {
        if (inner.isAlive) {
            val snap = inner.bufferSnapshot().trimEnd()
            if (snap.isNotBlank()) {
                markAlive(true)
                return snap
            }
        }
        val probe = TmuxAndy.probePane(sessionId, historyLines = TMUX_CAPTURE_HISTORY_LINES)
        markAlive(probe.alive)
        if (!probe.alive) return inner.bufferSnapshot()
        return probe.content.trimEnd()
    }

    /**
     * Close the local KetraTerm viewer only; the tmux session keeps running.
     * Drops the viewer forwarding with it, leaving the liveness watch to fall back to
     * tmux until a viewer is attached again.
     */
    fun releaseViewer() {
        viewerJob?.cancel()
        viewerJob = null
        inner.close()
    }

    /**
     * Spin up a fresh KetraTerm attach after [releaseViewer]. Rebinds forwarding to the new
     * emulator and reuses the existing liveness watch, so chat switches neither leak
     * observer loops nor leave the backend reading a closed viewer.
     */
    fun reattachViewer(appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot()) {
        if (isViewerAlive) return
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before reattaching"
        }
        TmuxAndy.ensureServerConfigured()
        // Reached whenever the old viewer is not alive — which includes its PTY dying on its
        // own, not just [releaseViewer]. In that case its KetraTerm session, Swing widget and
        // render-worker thread are all still up, so replacing the reference without closing
        // leaked one of each per chat switch.
        runCatching { inner.close() }
        inner = newInner(appearance)
        inner.start(TmuxAndy.attachArgv(sessionId), cwd = resolveTerminalWorkingDirectory(null), env = emptyMap())
        observeViewer(inner)
        ensureLivenessWatch()
    }

    /**
     * Tear down the local viewer and observer coroutines without killing the tmux session.
     * Used when Andy fully drops its handle for a chat.
     */
    fun abandonLocalResources() {
        viewerJob?.cancel()
        viewerJob = null
        livenessJob?.cancel()
        livenessJob = null
        runCatching { inner.close() }
        scope.cancel()
    }

    override fun close() {
        viewerJob?.cancel()
        livenessJob?.cancel()
        releaseViewer()
        if (killTmuxOnClose) {
            TmuxAndy.killSession(sessionId)
        }
        if (_exitCode.value == null && !TmuxAndy.hasSession(sessionId)) {
            _exitCode.value = 0
        }
        scope.cancel()
    }

    /**
     * Republish [viewer]'s screen and title as this backend's own. Rebound per attach
     * because [reattachViewer] installs a new emulator.
     */
    private fun observeViewer(viewer: KetraTermBackend) {
        historyBridgePending.set(true)
        viewerJob?.cancel()
        viewerJob = scope.launch {
            launch {
                viewer.bufferSnapshots.collect { snap ->
                    // The viewer only emits while its PTY lives, so an emission is proof
                    // the tmux session it attached to is still up.
                    markAlive(true)
                    val trimmed = snap.trimEnd()
                    if (trimmed != lastSnapshot) {
                        lastSnapshot = trimmed
                        _bufferSnapshots.emit(trimmed)
                    }
                }
            }
            launch {
                viewer.windowTitle.collect { title ->
                    if (title.isNotBlank() && title != _windowTitle.value) _windowTitle.value = title
                }
            }
        }
    }

    /**
     * Watches for session end, and stands in for the viewer when none is mounted.
     *
     * A live viewer PTY means a live session, so the common case costs a boolean read.
     * Only a released (or dead) viewer falls through to tmux, and then at background
     * cadence — a chat with no viewer is not the one on screen.
     */
    private fun ensureLivenessWatch() {
        if (livenessJob?.isActive == true) return
        livenessJob = scope.launch {
            while (isActive) {
                if (inner.isAlive) {
                    markAlive(true)
                    delay(VIEWER_LIVENESS_MS)
                    continue
                }
                val probe = TmuxAndy.probePane(sessionId, historyLines = TMUX_CAPTURE_HISTORY_LINES)
                markAlive(probe.alive)
                if (!probe.alive) break
                val snap = probe.content.trimEnd()
                if (snap != lastSnapshot) {
                    lastSnapshot = snap
                    _bufferSnapshots.emit(snap)
                }
                if (probe.title.isNotBlank() && probe.title != _windowTitle.value) {
                    _windowTitle.value = probe.title
                }
                delay(if (foreground.get()) TMUX_FALLBACK_SCRAPE_MS else TMUX_BACKGROUND_SCRAPE_MS)
            }
            if (!isActive) return@launch
            // Session ended: flush whatever the local viewer still holds, then report exit.
            val finalSnap = inner.bufferSnapshot()
            if (finalSnap.isNotBlank() && finalSnap != lastSnapshot) _bufferSnapshots.emit(finalSnap)
            if (_exitCode.value == null) _exitCode.value = 0
        }
    }

    private fun newInner(appearance: TerminalAppearanceSnapshot): KetraTermBackend =
        KetraTermBackend(
            sessionId = sessionId,
            cols = cols,
            rows = rows,
            appearance = appearance,
        ).also { backend ->
            // Read [foreground] through the lambda rather than copying it: the manager
            // reassigns the whole AtomicBoolean when a chat is bound, so a captured
            // reference would go on reporting the cadence of a previous binding.
            backend.foregroundProvider = { foreground.get() }
        }

    private companion object {
        /** Fork-free liveness poll while a viewer is attached: a `Process.isAlive` read. */
        private const val VIEWER_LIVENESS_MS = 500L

        /** tmux fallback cadence with no viewer, for the chat the user is looking at. */
        private const val TMUX_FALLBACK_SCRAPE_MS = 1_000L
        private const val TMUX_BACKGROUND_SCRAPE_MS = 3_000L
        private const val TMUX_CAPTURE_HISTORY_LINES = 80

        /**
         * How long [isAlive] trusts the watch loop's liveness reading. Comfortably above the
         * background cadence so backgrounded chats stay cache-served rather than forking.
         */
        private const val LIVENESS_CACHE_MS = 4_000L
    }
}
