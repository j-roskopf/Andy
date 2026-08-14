package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.rust.RustTerminalBackend
import app.andy.terminal.rust.RustTerminalNative
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
 * GUI viewer: attaches the Rust terminal engine to an existing `tmux -L andy` session.
 *
 * The agent process is owned by the detached tmux session; this backend provides the
 * Compose terminal view, keystroke path, and the screen that status detection reads.
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
    private var inner: RustTerminalBackend? = null

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

    init {
        spawnInner(appearance)
    }

    override val isAlive: Boolean
        get() {
            if (inner?.isAlive == true) return true
            val age = System.currentTimeMillis() - lastAliveAtMs
            val fresh = livenessJob?.isActive == true && age <= LIVENESS_CACHE_MS
            if (fresh) return lastAliveSeen
            return TmuxAndy.hasSession(sessionId).also { markAlive(it) }
        }

    private fun markAlive(alive: Boolean) {
        lastAliveSeen = alive
        lastAliveAtMs = System.currentTimeMillis()
    }

    val isViewerAlive: Boolean
        get() = inner?.isAlive == true

    override val pid: Long?
        get() = inner?.pid

    override val oscProgress: StateFlow<String>
        get() = inner?.oscProgress ?: EmptyOscProgress

    fun rustTerminal(): RustTerminalBackend? = inner

    fun hasLiveViewer(): Boolean = isViewerAlive

    fun scrollbackAnsi(): String = inner?.scrollbackAnsi().orEmpty()

    fun scrollbackAnsiSnapshot(cursor: ScrollbackAnsiCursor? = null): ScrollbackAnsiSnapshot =
        inner?.scrollbackAnsiSnapshot(cursor)
            ?: ScrollbackAnsiSnapshot(content = "", startOffset = 0, endOffset = 0, epoch = 0)

    fun consumeHistoryBridge(): Boolean = historyBridgePending.getAndSet(false)

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        inner?.updateAppearance(appearance)
    }

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before attaching"
        }
        check(RustTerminalNative.isAvailable()) {
            "andy-terminal-engine native library missing"
        }
        TmuxAndy.ensureServerConfigured()
        TmuxAndy.exitCopyModeIfActive(sessionId)
        val viewer = inner ?: error("tmux attach viewer missing")
        viewer.start(
            TmuxAndy.attachArgv(sessionId),
            cwd = resolveTerminalWorkingDirectory(cwd),
            env = emptyMap(),
        )
        observeViewer(viewer)
        ensureLivenessWatch()
    }

    fun attach() {
        start(emptyList(), cwd = resolveTerminalWorkingDirectory(null), env = emptyMap())
    }

    override fun write(bytes: ByteArray) {
        inner?.write(bytes)
    }

    override fun writeText(text: String) {
        inner?.writeText(text)
    }

    override fun resize(cols: Int, rows: Int) {
        inner?.resize(cols, rows)
    }

    override fun bufferSnapshot(): String {
        if (isViewerAlive) {
            if (TmuxAndy.isPaneInCopyMode(sessionId)) {
                val probe = TmuxAndy.probePane(sessionId, historyLines = 0)
                markAlive(probe.alive)
                if (probe.alive) return probe.content.trimEnd()
            }
            val snap = inner?.bufferSnapshot().orEmpty().trimEnd()
            if (snap.isNotBlank()) {
                markAlive(true)
                return snap
            }
        }
        val probe = TmuxAndy.probePane(sessionId, historyLines = TMUX_CAPTURE_HISTORY_LINES)
        markAlive(probe.alive)
        if (!probe.alive) return inner?.bufferSnapshot().orEmpty()
        return probe.content.trimEnd()
    }

    fun releaseViewer() {
        viewerJob?.cancel()
        viewerJob = null
        TmuxAndy.exitCopyModeIfActive(sessionId)
        inner?.close()
        inner = null
    }

    fun reattachViewer(appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot()) {
        if (isViewerAlive) return
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before reattaching"
        }
        TmuxAndy.ensureServerConfigured()
        TmuxAndy.exitCopyModeIfActive(sessionId)
        runCatching { inner?.close() }
        spawnInner(appearance)
        val viewer = inner ?: error("tmux attach viewer missing after reattach")
        viewer.start(
            TmuxAndy.attachArgv(sessionId),
            cwd = resolveTerminalWorkingDirectory(null),
            env = emptyMap(),
        )
        observeViewer(viewer)
        ensureLivenessWatch()
    }

    fun abandonLocalResources() {
        viewerJob?.cancel()
        viewerJob = null
        livenessJob?.cancel()
        livenessJob = null
        runCatching { inner?.close() }
        inner = null
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

    private fun spawnInner(appearance: TerminalAppearanceSnapshot) {
        inner = RustTerminalBackend(
            sessionId = sessionId,
            cols = cols,
            rows = rows,
            appearance = appearance,
            forwardMouseToApplication = true,
        ).also { backend ->
            backend.foregroundProvider = { foreground.get() }
        }
    }

    private fun observeViewer(viewer: RustTerminalBackend) {
        historyBridgePending.set(true)
        viewerJob?.cancel()
        viewerJob = scope.launch {
            launch {
                viewer.bufferSnapshots.collect { snap ->
                    markAlive(true)
                    val trimmed = if (TmuxAndy.isPaneInCopyMode(sessionId)) {
                        TmuxAndy.probePane(sessionId, historyLines = 0).content.trimEnd()
                    } else {
                        snap.trimEnd()
                    }
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
                if (isViewerAlive) {
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
            val finalSnap = inner?.bufferSnapshot().orEmpty()
            if (finalSnap.isNotBlank() && finalSnap != lastSnapshot) _bufferSnapshots.emit(finalSnap)
            if (_exitCode.value == null) _exitCode.value = 0
        }
    }

    private companion object {
        private val EmptyOscProgress: StateFlow<String> = MutableStateFlow("").asStateFlow()
        private const val VIEWER_LIVENESS_MS = 500L
        private const val TMUX_FALLBACK_SCRAPE_MS = 1_000L
        private const val TMUX_BACKGROUND_SCRAPE_MS = 3_000L
        private const val TMUX_CAPTURE_HISTORY_LINES = 80
        private const val LIVENESS_CACHE_MS = 4_000L
    }
}
