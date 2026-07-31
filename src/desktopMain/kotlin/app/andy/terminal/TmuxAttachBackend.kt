package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
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
 * GUI viewer: attaches BossTerm/Pty4J to an existing `tmux -L andy` session.
 *
 * The agent process is owned by the detached tmux session (created by
 * [TmuxAndy.newSession] or [TmuxAgentBackend]); this backend provides the Compose
 * terminal view, the local keystroke path, and the screen that status detection reads.
 *
 * While a viewer is attached, buffer/title/liveness come from the BossTerm emulator.
 * tmux is still polled when no viewer is mounted (chat released to the background).
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
    private var inner: BossTermBackend = newInner(appearance)

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    private val _windowTitle = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private var viewerJob: Job? = null
    private var livenessJob: Job? = null

    @Volatile private var lastSnapshot: String = ""

    private val historyBridgePending = AtomicBoolean(true)

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

    /** True while the local BossTerm/PTY viewer process attached to tmux is still running. */
    val isViewerAlive: Boolean
        get() = inner.isAlive

    override val pid: Long? get() = inner.pid
    override val oscProgress: StateFlow<String> get() = inner.oscProgress

    /**
     * Compose view for this attach. Available as soon as the BossTerm session exists
     * (including while the PTY is still connecting); null after [releaseViewer]/[close].
     */
    fun terminalView(): AndyTerminalView? =
        if (BossTermAccess.tab(inner.terminalViewState()) != null) inner.toTerminalView() else null

    fun scrollbackAnsi(): String = inner.scrollbackAnsi()

    fun scrollbackAnsiSnapshot(cursor: ScrollbackAnsiCursor? = null): ScrollbackAnsiSnapshot =
        inner.scrollbackAnsiSnapshot(cursor)

    fun consumeHistoryBridge(): Boolean = historyBridgePending.getAndSet(false)

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) = inner.updateAppearance(appearance)

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before attaching"
        }
        TmuxAndy.ensureServerConfigured()
        val attachCwd = resolveTerminalWorkingDirectory(cwd)
        inner.start(TmuxAndy.attachArgv(sessionId), cwd = attachCwd, env = emptyMap())
        observeViewer(inner)
        ensureLivenessWatch()
    }

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

    /** Close the local BossTerm viewer only; the tmux session keeps running. */
    fun releaseViewer() {
        viewerJob?.cancel()
        viewerJob = null
        inner.close()
    }

    /** Spin up a fresh BossTerm attach after [releaseViewer]. */
    fun reattachViewer(appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot()) {
        if (isViewerAlive) return
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before reattaching"
        }
        TmuxAndy.ensureServerConfigured()
        runCatching { inner.close() }
        inner = newInner(appearance)
        inner.start(TmuxAndy.attachArgv(sessionId), cwd = resolveTerminalWorkingDirectory(null), env = emptyMap())
        observeViewer(inner)
        ensureLivenessWatch()
    }

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

    private fun observeViewer(viewer: BossTermBackend) {
        historyBridgePending.set(true)
        viewerJob?.cancel()
        viewerJob = scope.launch {
            launch {
                viewer.bufferSnapshots.collect { snap ->
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
            val finalSnap = inner.bufferSnapshot()
            if (finalSnap.isNotBlank() && finalSnap != lastSnapshot) _bufferSnapshots.emit(finalSnap)
            if (_exitCode.value == null) _exitCode.value = 0
        }
    }

    private fun newInner(appearance: TerminalAppearanceSnapshot): BossTermBackend =
        BossTermBackend(
            sessionId = sessionId,
            cols = cols,
            rows = rows,
            appearance = appearance,
            agentCliMode = true,
            forwardMouseToApplication = true,
        ).also { backend ->
            backend.foregroundProvider = { foreground.get() }
        }

    private companion object {
        private const val VIEWER_LIVENESS_MS = 500L
        private const val TMUX_FALLBACK_SCRAPE_MS = 1_000L
        private const val TMUX_BACKGROUND_SCRAPE_MS = 3_000L
        private const val TMUX_CAPTURE_HISTORY_LINES = 80
        private const val LIVENESS_CACHE_MS = 4_000L
    }
}
