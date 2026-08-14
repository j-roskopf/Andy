package app.andy.terminal.rust

import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.AndyPty
import app.andy.terminal.AndyPtyHandle
import app.andy.terminal.ScrollbackAnsiCursor
import app.andy.terminal.ScrollbackAnsiSnapshot
import app.andy.terminal.ScrollbackAnsiTee
import app.andy.terminal.TerminalSession
import app.andy.terminal.buildTerminalLaunchEnvironment
import app.andy.terminal.resolveTerminalWorkingDirectory
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
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * PTY session using the Rust `alacritty_terminal` engine + Compose canvas.
 */
class RustTerminalBackend(
    override val sessionId: String,
    cols: Int = 120,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
    /** Forward mouse protocol to an outer application such as tmux. */
    private val forwardMouseToApplication: Boolean = false,
) : TerminalSession, RustTerminalRenderable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val appearanceRef = AtomicReference(appearance)
    private val scrollbackTee = ScrollbackAnsiTee()
    private val processHandleRef = AtomicReference<AndyPtyHandle?>(null)
    private val gridCols = AtomicInteger(cols.coerceAtLeast(1))
    private val gridRows = AtomicInteger(rows.coerceAtLeast(1))
    private val engine = RustTerminalEngine(gridCols.get(), gridRows.get())
    private val dirty = AtomicBoolean(true)
    private val frameVersion = AtomicLong(0)
    private val paintFrame = RustTerminalFrame()
    private val stagingFrame = RustTerminalFrame()
    private val publishLock = Any()
    private val mouseFlagsCached = AtomicInteger(0)

    private var readJob: Job? = null
    private var waitJob: Job? = null
    private var scrapeJob: Job? = null
    private var paintJob: Job? = null

    @Volatile
    var foregroundProvider: () -> Boolean = { true }

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    private val _windowTitle = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _oscProgress = MutableStateFlow("")
    override val oscProgress: StateFlow<String> = _oscProgress.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    override val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    override val isAlive: Boolean
        get() = processHandleRef.get()?.isAlive() == true

    override val pid: Long?
        get() = processHandleRef.get()?.getPid()?.takeIf { it > 0 }

    fun appearance(): TerminalAppearanceSnapshot = appearanceRef.get()

    fun forwardsMouseToApplication(): Boolean = forwardMouseToApplication

    override fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        appearanceRef.set(appearance)
        runCatching { engine.setPalette(appearance.toRustPaletteArgb()) }
        dirty.set(true)
    }

    fun scrollbackAnsi(): String = scrollbackTee.snapshot()

    fun scrollbackAnsiSnapshot(cursor: ScrollbackAnsiCursor? = null): ScrollbackAnsiSnapshot {
        return scrollbackTee.snapshotWithOffsets(cursor).copy(
            columns = gridCols.get(),
            rows = gridRows.get(),
        )
    }

    override fun copyPaintFrame(into: RustTerminalFrame) {
        synchronized(publishLock) {
            into.copyFrom(paintFrame)
        }
    }

    fun frameVersion(): Long = frameVersion.get()

    override fun mouseFlags(): Int = mouseFlagsCached.get()

    override fun scrollDisplay(delta: Int) {
        engine.scrollDisplay(delta)
        dirty.set(true)
    }

    fun scrollToBottom() {
        engine.scrollToBottom()
        dirty.set(true)
    }

    fun displayOffset(): Int = engine.displayOffset()

    fun markDirty() {
        dirty.set(true)
    }

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        require(argv.isNotEmpty()) { "argv must not be empty" }
        check(RustTerminalNative.isAvailable()) {
            "andy-terminal-engine native library is not available on this platform"
        }

        engine.setPalette(appearanceRef.get().toRustPaletteArgb())

        // Seed from the normal launch environment (process + login shell) before applying
        // per-start overrides. Tmux viewers often pass emptyMap() and still need TMUX_TMPDIR /
        // locale from the host so the client can find the andy server socket.
        val environment = HashMap(buildTerminalLaunchEnvironment(env)).apply {
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                putIfAbsent("LC_CTYPE", "UTF-8")
            }
        }
        val handle = AndyPty.spawn(
            command = argv.first(),
            arguments = argv.drop(1),
            environment = environment,
            workingDirectory = resolveTerminalWorkingDirectory(cwd),
            cols = gridCols.get(),
            rows = gridRows.get(),
        )
        processHandleRef.set(handle)

        readJob = scope.launch { readLoop(handle) }
        waitJob = scope.launch {
            val code = runCatching { handle.waitFor() }.getOrElse { -1 }
            _exitCode.compareAndSet(null, code)
        }
        scrapeJob = scope.launch { scrapeLoop() }
        paintJob = scope.launch { paintLoop() }
        dirty.set(true)
    }

    override fun write(bytes: ByteArray) {
        val handle = processHandleRef.get() ?: return
        if (engine.displayOffset() > 0) {
            engine.scrollToBottom()
        }
        scope.launch {
            runCatching { handle.writeBytes(bytes) }
        }
        dirty.set(true)
    }

    override fun resize(cols: Int, rows: Int) {
        val c = cols.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        gridCols.set(c)
        gridRows.set(r)
        // Bucketed by agent-CLI vs. plain shell (see forwardMouseToApplication) — an
        // Actions-dock shell pane is typically a very different width than a chat pane,
        // and seeding one from the other's last size just trades a too-small first paint
        // for a too-wide one.
        if (forwardMouseToApplication) {
            lastKnownAgentGridCols.set(c)
            lastKnownAgentGridRows.set(r)
        } else {
            lastKnownShellGridCols.set(c)
            lastKnownShellGridRows.set(r)
        }
        engine.resize(c, r)
        val handle = processHandleRef.get()
        if (handle != null) {
            scope.launch {
                runCatching { handle.resize(c, r) }
            }
        }
        dirty.set(true)
    }

    override fun bufferSnapshot(): String = engine.viewportText()

    override fun close() {
        readJob?.cancel()
        scrapeJob?.cancel()
        paintJob?.cancel()
        waitJob?.cancel()
        val handle = processHandleRef.getAndSet(null)
        if (handle != null) {
            runCatching {
                runBlocking { handle.kill() }
            }
        }
        runCatching { engine.close() }
        _exitCode.compareAndSet(null, CLOSED_EXIT_CODE)
        scope.cancel()
    }

    private suspend fun readLoop(handle: AndyPtyHandle) {
        // Keep reading until EOF — isAlive() can flip false while the pipe still has
        // final buffered output from a short-lived process.
        while (scope.isActive) {
            val bytes = runCatching { handle.read() }.getOrNull() ?: break
            if (bytes.isEmpty()) continue
            scrollbackTee.append(bytes, 0, bytes.size)
            engine.advance(bytes)
            mouseFlagsCached.set(engine.mouseFlags())
            dirty.set(true)
            refreshOscFromTee()
        }
    }

    private suspend fun scrapeLoop() {
        var last = ""
        while (scope.isActive) {
            val snap = bufferSnapshot()
            if (snap != last) {
                last = snap
                _bufferSnapshots.emit(snap)
            }
            refreshOscFromTee()
            delay(if (foregroundProvider()) 250L else 1_000L)
        }
    }

    private suspend fun paintLoop() {
        val fps = System.getProperty("andy.terminal.repaint.fps")?.toIntOrNull() ?: DEFAULT_FPS
        val intervalMs = if (fps <= 0) 1L else (1000L / fps).coerceAtLeast(1L)
        var syncStartedAt = 0L
        while (scope.isActive) {
            val syncBytes = engine.syncBufferedBytes()
            if (syncBytes > 0) {
                if (syncStartedAt == 0L) syncStartedAt = System.currentTimeMillis()
                if (System.currentTimeMillis() - syncStartedAt > SYNC_TIMEOUT_MS) {
                    engine.stopSync()
                    syncStartedAt = 0L
                    dirty.set(true)
                } else {
                    delay(intervalMs)
                    continue
                }
            } else {
                syncStartedAt = 0L
            }

            if (dirty.compareAndSet(true, false)) {
                if (engine.fillFrame(stagingFrame)) {
                    mouseFlagsCached.set(engine.mouseFlags())
                    synchronized(publishLock) {
                        paintFrame.copyFrom(stagingFrame)
                    }
                    _frameTick.value = frameVersion.incrementAndGet()
                }
            }
            delay(intervalMs)
        }
    }

    private fun refreshOscFromTee() {
        val teedTitle = scrollbackTee.latestOscTitle()
        if (teedTitle.isNotEmpty()) _windowTitle.value = teedTitle
        _oscProgress.value = scrollbackTee.latestOscProgress()
    }

    companion object {
        const val CLOSED_EXIT_CODE: Int = -1
        const val DEFAULT_MAX_HISTORY: Int = 10_000
        const val SCROLLBACK_CAPTURE_ROWS: Int = 500
        const val SCROLLBACK_BACKGROUND_CAPTURE_ROWS: Int = 80
        private const val DEFAULT_FPS: Int = 60
        private const val SYNC_TIMEOUT_MS: Long = 150L

        // Compose only learns a terminal's real pixel size after the canvas mounts and
        // resizes it post-launch — too late for a CLI that dumps its whole replayed
        // history the instant it starts (e.g. a quiet `--resume`), which locks its TUI
        // into whatever tiny grid the PTY opened at. Seed new spawns with the last size
        // a terminal of the *same kind* actually negotiated instead of a fixed guess —
        // separate buckets so an Actions-dock shell (wide, no sidebar) never seeds an
        // agent chat pane (narrower) or vice versa.
        private val lastKnownAgentGridCols = AtomicInteger(120)
        private val lastKnownAgentGridRows = AtomicInteger(32)
        private val lastKnownShellGridCols = AtomicInteger(120)
        private val lastKnownShellGridRows = AtomicInteger(32)
        fun lastKnownGridSize(agentCli: Boolean): Pair<Int, Int> = if (agentCli) {
            lastKnownAgentGridCols.get() to lastKnownAgentGridRows.get()
        } else {
            lastKnownShellGridCols.get() to lastKnownShellGridRows.get()
        }
    }
}
