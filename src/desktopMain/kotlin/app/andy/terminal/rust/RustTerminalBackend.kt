package app.andy.terminal.rust

import ai.rever.bossterm.compose.DesktopProcessService
import ai.rever.bossterm.compose.PlatformServices
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.terminal.ScrollbackAnsiCursor
import app.andy.terminal.ScrollbackAnsiSnapshot
import app.andy.terminal.ScrollbackAnsiTee
import app.andy.terminal.TerminalSession
import app.andy.terminal.resolveTerminalWorkingDirectory
import app.andy.terminal.scrubInheritedTerminalEnvironment
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * DirectPty session using the Rust `alacritty_terminal` engine + Compose canvas.
 *
 * Enabled with `-Dandy.terminal.engine=rust`. BossTerm remains the default.
 */
class RustTerminalBackend(
    override val sessionId: String,
    cols: Int = 120,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
) : TerminalSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val appearanceRef = AtomicReference(appearance)
    private val scrollbackTee = ScrollbackAnsiTee()
    private val processHandleRef = AtomicReference<PlatformServices.ProcessService.ProcessHandle?>(null)
    private val gridCols = AtomicInteger(cols.coerceAtLeast(1))
    private val gridRows = AtomicInteger(rows.coerceAtLeast(1))
    private val engine = RustTerminalEngine(gridCols.get(), gridRows.get())
    private val dirty = AtomicBoolean(true)
    private val frameVersion = AtomicLong(0)
    private val paintFrame = RustTerminalFrame()
    private val stagingFrame = RustTerminalFrame()
    private val publishLock = Any()

    private var readJob: Job? = null
    private var waitJob: Job? = null
    private var scrapeJob: Job? = null
    private var paintJob: Job? = null

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    private val _windowTitle = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _oscProgress = MutableStateFlow("")
    override val oscProgress: StateFlow<String> = _oscProgress.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    /** Bumps when a new [paintFrame] is ready for Compose. */
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    override val isAlive: Boolean
        get() = processHandleRef.get()?.isAlive() == true

    override val pid: Long?
        get() = processHandleRef.get()?.getPid()?.takeIf { it > 0 }

    fun appearance(): TerminalAppearanceSnapshot = appearanceRef.get()

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        appearanceRef.set(appearance)
    }

    fun scrollbackAnsiSnapshot(cursor: ScrollbackAnsiCursor? = null): ScrollbackAnsiSnapshot {
        return scrollbackTee.snapshotWithOffsets(cursor).copy(
            columns = gridCols.get(),
            rows = gridRows.get(),
        )
    }

    /** Copy the latest paint buffer into [into] (UI thread). */
    fun copyPaintFrame(into: RustTerminalFrame) {
        synchronized(publishLock) {
            into.copyFrom(paintFrame)
        }
    }

    fun frameVersion(): Long = frameVersion.get()

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        require(argv.isNotEmpty()) { "argv must not be empty" }

        val environment = HashMap(env).apply {
            scrubInheritedTerminalEnvironment(this)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                put("LC_CTYPE", "UTF-8")
            }
        }
        val config = PlatformServices.ProcessService.ProcessConfig(
            command = argv.first(),
            arguments = argv.drop(1),
            environment = environment,
            workingDirectory = resolveTerminalWorkingDirectory(cwd),
        )
        val handle = runBlocking {
            DesktopProcessService().spawnProcess(config)
        } ?: error("Failed to spawn PTY for Rust terminal engine")
        processHandleRef.set(handle)

        // Align PTY size with engine.
        runBlocking {
            runCatching { handle.resize(gridCols.get(), gridRows.get()) }
        }

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
        scope.launch {
            runCatching { handle.writeBytes(bytes) }
        }
        // Eager paint after input so keystroke echo feels instant.
        dirty.set(true)
    }

    override fun resize(cols: Int, rows: Int) {
        val c = cols.coerceAtLeast(1)
        val r = rows.coerceAtLeast(1)
        gridCols.set(c)
        gridRows.set(r)
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

    private suspend fun readLoop(handle: PlatformServices.ProcessService.ProcessHandle) {
        while (scope.isActive && handle.isAlive()) {
            val chunk = runCatching { handle.read() }.getOrNull() ?: break
            if (chunk.isEmpty()) continue
            val bytes = chunk.toByteArray(StandardCharsets.UTF_8)
            scrollbackTee.append(bytes, 0, bytes.size)
            engine.advance(bytes)
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
            delay(250)
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
        private const val DEFAULT_FPS: Int = 60
        private const val SYNC_TIMEOUT_MS: Long = 150L

        fun isEnabled(): Boolean =
            System.getProperty("andy.terminal.engine")
                ?.equals("rust", ignoreCase = true) == true &&
                RustTerminalNative.isAvailable()
    }
}
