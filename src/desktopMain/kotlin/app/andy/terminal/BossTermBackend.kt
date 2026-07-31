package app.andy.terminal

import ai.rever.bossterm.compose.EmbeddableTerminalState
import ai.rever.bossterm.compose.PlatformServices
import ai.rever.bossterm.compose.settings.TerminalSettingsOverride
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-platform PTY session backed by BossTerm (Compose-native) + Pty4J.
 * Agents and Actions both go through this backend.
 */
class BossTermBackend(
    override val sessionId: String,
    cols: Int = 120,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
    /** Agent CLIs on the alternate screen — tighter settings and PTY sanitization. */
    private val agentCliMode: Boolean = false,
    /** Forward mouse protocol events to an outer application such as tmux. */
    private val forwardMouseToApplication: Boolean = false,
) : TerminalSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val terminalState = EmbeddableTerminalState()
    private val appearanceRef = AtomicReference(appearance)
    private val scrollbackTee = ScrollbackAnsiTee()
    private val argvRef = AtomicReference<List<String>>(emptyList())
    private val cwdRef = AtomicReference<String?>(null)
    private val envRef = AtomicReference<Map<String, String>>(emptyMap())
    private val processHandleRef = AtomicReference<PlatformServices.ProcessService.ProcessHandle?>(null)
    /** Live grid — must track [resize] so raw-scrollback layout markers match the TUI. */
    private val gridCols = AtomicReference(cols.coerceAtLeast(1))
    private val gridRows = AtomicReference(rows.coerceAtLeast(1))
    private var waitJob: Job? = null
    private var scrapeJob: Job? = null
    private var titleJob: Job? = null
    private var historyController: TerminalHistoryController? = null
    private var frameLimiter: TerminalFrameLimiter? = null

    private val platformServices = AndyBossTermPlatformServices(
        argvProvider = { argvRef.get() },
        cwdProvider = { cwdRef.get() },
        envOverridesProvider = { envRef.get() },
        scrollbackTee = scrollbackTee,
        agentCliMode = agentCliMode,
        onHandle = { processHandleRef.set(it) },
    )

    /**
     * Whether this session is the one the user is looking at. Backgrounded chats still need
     * their screen observed (status detection reads [bufferSnapshots]) but not four times a
     * second. Wired by [TmuxAttachBackend] for the GUI viewer; direct sessions stay foreground.
     */
    @Volatile
    var foregroundProvider: () -> Boolean = { true }

    private val localGeneration = AtomicLong(0L)

    private fun screenGeneration(): Long = scrollbackTee.outputGeneration() + localGeneration.get()

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    private val _windowTitle = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _oscProgress = MutableStateFlow("")
    override val oscProgress: StateFlow<String> = _oscProgress.asStateFlow()

    override val isAlive: Boolean
        get() = processHandleRef.get()?.isAlive() == true || BossTermAccess.isProcessAlive(terminalState)

    override val pid: Long?
        get() = processHandleRef.get()?.getPid()?.takeIf { it > 0 } ?: BossTermAccess.pid(terminalState)

    /** Compose-native terminal state for [EmbeddableTerminal]. */
    fun terminalViewState(): EmbeddableTerminalState = terminalState

    fun settingsOverride(): TerminalSettingsOverride =
        appearanceRef.get().toBossTermSettingsOverride(
            scrollbackLines = DEFAULT_MAX_HISTORY,
            agentCliMode = agentCliMode,
            forwardMouseToApplication = forwardMouseToApplication,
        )

    fun platformServices(): PlatformServices = platformServices

    fun forwardsMouseToApplication(): Boolean = forwardMouseToApplication

    /** Shell command string passed to EmbeddableTerminal (ProcessService overrides argv). */
    fun embedCommand(): String = argvRef.get().firstOrNull() ?: "/bin/sh"

    fun embedWorkingDirectory(): String? = cwdRef.get()

    fun embedEnvironment(): Map<String, String> = envRef.get()

    /** Raw teed PTY stdout (includes in-place TUI redraws). */
    fun scrollbackAnsi(): String = scrollbackTee.snapshot()

    fun scrollbackAnsiSnapshot(cursor: ScrollbackAnsiCursor? = null): ScrollbackAnsiSnapshot {
        return scrollbackTee.snapshotWithOffsets(cursor).copy(
            columns = gridCols.get(),
            rows = gridRows.get(),
        )
    }

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        appearanceRef.set(appearance)
        // Live theme updates apply on next EmbeddableTerminal recomposition via settingsOverride.
    }

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        require(argv.isNotEmpty()) { "argv must not be empty" }
        argvRef.set(argv)
        cwdRef.set(cwd)
        envRef.set(env)

        val settings = appearanceRef.get().toBossTermSettings(
            scrollbackLines = DEFAULT_MAX_HISTORY,
            agentCliMode = agentCliMode,
            forwardMouseToApplication = forwardMouseToApplication,
        )
        BossTermAccess.initialize(
            state = terminalState,
            settings = settings,
            command = argv.first(),
            workingDirectory = resolveTerminalWorkingDirectory(cwd),
            environment = env,
            onOutput = null,
            onExit = { code -> _exitCode.compareAndSet(null, code) },
            platformServices = platformServices,
        )

        // initializeSession spawns the PTY on a BossTerm coroutine. Wait briefly so
        // callers that immediately ask for isAlive / terminalView see a live handle.
        runBlockingAwaitHandle(timeoutMs = 5_000)
        runBlockingAwaitHistoryController()
        startFrameLimiter()

        waitJob = scope.launch {
            val handle = processHandleRef.get()
            if (handle == null) {
                _exitCode.compareAndSet(null, CLOSED_EXIT_CODE)
                return@launch
            }
            val code = runCatching { handle.waitFor() }.getOrElse { -1 }
            _exitCode.compareAndSet(null, code)
            scrapeJob?.cancel()
        }

        titleJob = scope.launch {
            // Title flow appears once the session tab exists.
            var flow = BossTermAccess.windowTitleFlow(terminalState)
            var spins = 0
            while (flow == null && spins < 200) {
                delay(25)
                flow = BossTermAccess.windowTitleFlow(terminalState)
                spins++
            }
            flow?.collect { title ->
                if (title.isNotBlank()) _windowTitle.value = title
            }
        }

        scrapeJob = scope.launch {
            var last = ""
            var lastGeneration = -1L
            while (isActive && isAlive) {
                val generation = screenGeneration()
                if (generation != lastGeneration) {
                    lastGeneration = generation
                    val snap = bufferSnapshot()
                    if (snap != last) {
                        last = snap
                        _bufferSnapshots.emit(snap)
                    }
                    refreshOscFromTee()
                }
                delay(if (foregroundProvider()) FOREGROUND_SCRAPE_MS else BACKGROUND_SCRAPE_MS)
            }
            val finalSnap = bufferSnapshot()
            if (finalSnap != last) _bufferSnapshots.emit(finalSnap)
            refreshOscFromTee()
        }
    }

    override fun write(bytes: ByteArray) {
        BossTermAccess.writeBytes(terminalState, bytes)
        // Keystroke echo must not wait for the next frame boundary.
        frameLimiter?.flushNow()
    }

    override fun writeText(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    override fun resize(cols: Int, rows: Int) {
        if (cols > 0) gridCols.set(cols)
        if (rows > 0) gridRows.set(rows)
        localGeneration.incrementAndGet()
        BossTermAccess.resize(terminalState, gridCols.get(), gridRows.get(), scope)
    }

    override fun bufferSnapshot(): String = BossTermAccess.screenText(terminalState)

    private fun runBlockingAwaitHandle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (processHandleRef.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    private fun publishExitCode() {
        val observed = processHandleRef.get()?.getExitCode()
        _exitCode.compareAndSet(null, observed ?: CLOSED_EXIT_CODE)
    }

    private fun refreshOscFromTee() {
        val teedTitle = scrollbackTee.latestOscTitle()
        if (teedTitle.isNotEmpty()) _windowTitle.value = teedTitle
        _oscProgress.value = scrollbackTee.latestOscProgress()
    }

    override fun close() {
        frameLimiter?.close()
        frameLimiter = null
        scrapeJob?.cancel()
        titleJob?.cancel()
        waitJob?.cancel()
        val handle = processHandleRef.getAndSet(null)
        // Kill the PTY explicitly and wait — BossTerm dispose alone is async enough that
        // a rapid release→reattach race can leave two tmux clients briefly attached.
        if (handle != null) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    handle.kill()
                }
            }
        }
        runCatching { terminalState.dispose() }
        historyController?.close()
        historyController = null
        publishExitCode()
        scope.cancel()
    }

    /**
     * Cap full-grid re-renders. BossTerm requests a redraw per emulated character and its own
     * backoff never engages at agent-CLI output rates; see [TerminalFrameLimiter].
     */
    private fun startFrameLimiter() {
        val display = BossTermAccess.display(terminalState) ?: return
        frameLimiter = TerminalFrameLimiter(
            display = display,
            foregroundProvider = { foregroundProvider() },
        ).also { it.start() }
    }

    /** Session-owned native history; rows are committed by BossTerm, never by a UI poller. */
    fun terminalHistory(): TerminalHistoryController? = historyController

    private fun runBlockingAwaitHistoryController() {
        val deadline = System.currentTimeMillis() + 5_000
        while (historyController == null && System.currentTimeMillis() < deadline) {
            BossTermAccess.textBuffer(terminalState)?.let { buffer ->
                historyController = TerminalHistoryController(buffer)
            } ?: Thread.sleep(25)
        }
    }

    companion object {
        const val CLOSED_EXIT_CODE: Int = -1
        private const val FOREGROUND_SCRAPE_MS = 250L
        private const val BACKGROUND_SCRAPE_MS = 1_000L
        const val DEFAULT_MAX_HISTORY: Int = 10_000
        const val SCROLLBACK_CAPTURE_ROWS: Int = 500
        const val SCROLLBACK_BACKGROUND_CAPTURE_ROWS: Int = 80
    }
}
