package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.pty.PtyConnector
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.session.TerminalSession as KetraSession
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-platform PTY session backed by KetraTerm + Pty4J.
 * Agents and Actions both go through this backend.
 */
class KetraTermBackend(
    override val sessionId: String,
    private val cols: Int = 120,
    private val rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
    /** Agent CLIs on the alternate screen — tighter insets and PTY sanitization. */
    private val agentCliMode: Boolean = false,
) : TerminalSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var process: PtyProcess? = null
    private var ketraSession: KetraSession? = null
    private var ptyConnector: PtyConnector? = null
    @Volatile private var swingTerminal: SwingTerminal? = null
    private var waitJob: Job? = null
    private var scrapeJob: Job? = null
    private val appearanceRef = AtomicReference(appearance)
    private val settingsRef = AtomicReference(
        swingSettingsFor(appearance, cols, rows, agentCliMode),
    )
    private val scrollbackTee = ScrollbackAnsiTee()
    private val historyStore by lazy { AndyCommandHistoryStore.shared() }

    /**
     * Whether this session is the one the user is looking at. Backgrounded chats still need
     * their screen observed (status detection reads [bufferSnapshots]) but not four times a
     * second. Wired by [TmuxAttachBackend] for the GUI viewer; direct sessions stay foreground.
     */
    @Volatile
    var foregroundProvider: () -> Boolean = { true }

    /**
     * Bumped by anything that can change the screen without host bytes arriving — today only
     * a resize, which reflows the grid. Read alongside [ScrollbackAnsiTee.outputGeneration]
     * so the scrape loop can tell "nothing happened" from "something did".
     */
    private val localGeneration = java.util.concurrent.atomic.AtomicLong(0L)

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
        get() = process?.isAlive == true

    override val pid: Long?
        get() = process?.pid()?.takeIf { it > 0 }

    fun swingTerminal(): SwingTerminal? = swingTerminal

    /** Raw teed PTY stdout (includes in-place TUI redraws). */
    fun scrollbackAnsi(): String = scrollbackTee.snapshot()

    /** Resolved readable scrollback suitable for durable replay after restart. */
    fun scrollbackExport(seenKeys: MutableSet<String>): String {
        val session = ketraSession ?: return ""
        return joinReadableLines(captureNewReadableLines(session.terminal, seenKeys))
    }

    fun captureReadableLines(seenKeys: MutableSet<String>): List<String> {
        val session = ketraSession ?: return emptyList()
        return captureNewReadableLines(session.terminal, seenKeys)
    }

    /** Newest [maxRows] rows of history + screen, styling intact, for durable replay. */
    fun captureStyledRows(maxRows: Int = SCROLLBACK_CAPTURE_ROWS): List<StyledTerminalRow> {
        val session = ketraSession ?: return emptyList()
        return runCatching { session.readStyledScrollbackRows(maxRows) }.getOrDefault(emptyList())
    }

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        appearanceRef.set(appearance)
        val settings = swingSettingsFor(appearance, cols, rows, agentCliMode)
        settingsRef.set(settings)
        val terminal = swingTerminal ?: return
        val session = ketraSession
        onSwingEdt {
            session?.setThemePalette(settings.palette)
            terminal.reloadSettings()
        }
    }

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        require(argv.isNotEmpty()) { "argv must not be empty" }
        AndyKetraTermConfig.ensureInitialized()

        // Merge caller overrides onto the process environment, then scrub IDE/proxy
        // vars. Scrub must run AFTER putAll — otherwise Cursor-injected NODE_OPTIONS
        // (js-debug bootloader) survives and makes Claude Code exit 1 immediately.
        val environment = HashMap(System.getenv()).apply {
            putAll(env)
            scrubInheritedTerminalEnvironment(this)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                put("LC_CTYPE", "UTF-8")
            }
        }

        val pty = PtyProcessBuilder()
            .setDirectory(resolveTerminalWorkingDirectory(cwd))
            .setCommand(argv.toTypedArray())
            .setEnvironment(environment)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
            .setUseWinConPty(true)
            .start()
        process = pty

        val connector = PtyConnector(pty)
        ptyConnector = connector
        val transport = if (agentCliMode) {
            AgentCliTeeTerminalConnector(connector, scrollbackTee)
        } else {
            TeeTerminalConnector(connector, scrollbackTee)
        }
        val buffer = TerminalBuffers.create(
            width = cols,
            height = rows,
            maxHistory = DEFAULT_MAX_HISTORY,
        )
        val hostSink = AndyHostEventSink(
            sessionId = sessionId,
            historyStore = historyStore,
            sessionProvider = { ketraSession },
            onWindowTitle = { title -> _windowTitle.value = title },
        )
        val session = KetraSession.create(
            terminal = buffer,
            connector = transport,
            hostEvents = hostSink,
            hostPolicy = HostPolicy(notificationPolicy = HostControlPolicy.ALLOW),
            inputPolicy = PtyOptions.defaultInputPolicy(),
        )
        ketraSession = session
        session.start(cols, rows)

        swingTerminal = onSwingEdt {
            SwingTerminal(
                settingsProvider = { settingsRef.get() },
                hostServices = andySwingHostServices(),
            ).also { terminal ->
                terminal.bind(session)
            }
        }

        waitJob = scope.launch {
            val code = runCatching { pty.waitFor() }.getOrElse { -1 }
            _exitCode.value = code
            scrapeJob?.cancel()
        }
        scrapeJob = scope.launch {
            var last = ""
            // Screen generation the last emitted snapshot was built from. Re-reading the
            // buffer when it has not moved meant walking every cell of the grid and building
            // a String only to compare it equal — per session, four times a second, forever.
            var lastGeneration = -1L
            while (isActive && pty.isAlive) {
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
        val connector = ptyConnector ?: return
        // Raw PTY stdin write. Avoid encodePaste — bracketed-paste / sanitization
        // would break typed submit and Actions shell command injection.
        runCatching { connector.write(bytes, 0, bytes.size) }
    }

    override fun writeText(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    override fun resize(cols: Int, rows: Int) {
        val session = ketraSession ?: return
        // Reflow changes the screen without the host sending anything, so the scrape loop
        // would otherwise sit on a stale snapshot until the next output arrived.
        localGeneration.incrementAndGet()
        onSwingEdt {
            runCatching { session.resize(cols, rows) }
        }
    }

    override fun bufferSnapshot(): String {
        val session = ketraSession ?: return ""
        return runCatching { session.terminal.getScreenAsString().trimEnd() }.getOrDefault("")
    }

    /**
     * Complete [exitCode] for anyone parked on it, using the process' real status when it
     * has one. Compare-and-set so a code already published by the wait loop always wins;
     * [CLOSED_EXIT_CODE] is only the fallback for "gone, and nobody ever reported how".
     */
    private fun publishExitCode() {
        val observed = process?.let { pty ->
            runCatching { if (pty.isAlive) null else pty.exitValue() }.getOrNull()
        }
        _exitCode.compareAndSet(null, observed ?: CLOSED_EXIT_CODE)
    }

    private fun refreshOscFromTee() {
        // The tee parses OSC as bytes arrive, so this is a field read rather than a
        // copy-and-rescan of the whole scrollback buffer on every poll.
        // HostEventSink titles win for live updates; tee backfills and supplies progress.
        val teedTitle = scrollbackTee.latestOscTitle()
        if (teedTitle.isNotEmpty()) _windowTitle.value = teedTitle
        _oscProgress.value = scrollbackTee.latestOscProgress()
    }

    override fun close() {
        scrapeJob?.cancel()
        // Callers park on [exitCode] for the whole turn, so it must always complete. The
        // wait loop normally reports it, but it cannot for a session closed before start,
        // or one whose waitFor() never returns — publish here so no waiter is stranded.
        publishExitCode()
        waitJob?.cancel()
        swingTerminal?.let { terminal ->
            onSwingEdt {
                runCatching { terminal.unbind() }
                runCatching { terminal.dispose() }
            }
        }
        ketraSession?.let { session -> runCatching { session.close() } }
        ptyConnector?.let { runCatching { it.close() } }
        process?.let { pty ->
            if (pty.isAlive) {
                pty.destroy()
                if (!pty.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    pty.destroyForcibly()
                }
            }
        }
        // Second pass: the process may have still been alive above, and a session closed
        // before start() has no process at all. Either way a waiter must be released.
        publishExitCode()
        swingTerminal = null
        ketraSession = null
        ptyConnector = null
        process = null
        scope.cancel()
    }

    companion object {
        /**
         * Reported by [close] when the session ends without the process ever supplying a
         * status — killed mid-flight, or closed before it was started. Matches the "unknown
         * exit" code the agent terminal manager already uses for a session it cannot query.
         */
        const val CLOSED_EXIT_CODE: Int = -1

        /** Screen-observation cadence for the chat on screen. */
        private const val FOREGROUND_SCRAPE_MS = 250L

        /**
         * Cadence for chats the user is not looking at. Their snapshots still drive status
         * detection, which reacts on the order of seconds, so paying 4Hz for them bought
         * nothing.
         */
        private const val BACKGROUND_SCRAPE_MS = 1_000L

        /** Scrollback lines retained by the emulator (~5MB ANSI soft-cap philosophy). */
        const val DEFAULT_MAX_HISTORY: Int = 10_000

        /**
         * Rows read per periodic scrollback capture. Far more than a screen can scroll
         * between flushes, so successive windows always overlap enough to stitch.
         */
        const val SCROLLBACK_CAPTURE_ROWS: Int = 500
        const val SCROLLBACK_BACKGROUND_CAPTURE_ROWS: Int = 80

        private fun swingSettingsFor(
            appearance: TerminalAppearanceSnapshot,
            cols: Int,
            rows: Int,
            agentCliMode: Boolean,
        ): io.github.ketraterm.ui.swing.settings.SwingSettings =
            if (agentCliMode) {
                appearance.toAgentCliSwingSettings(
                    columns = cols,
                    rows = rows,
                    scrollbackLines = DEFAULT_MAX_HISTORY,
                )
            } else {
                appearance.toSwingSettings(
                    columns = cols,
                    rows = rows,
                    scrollbackLines = DEFAULT_MAX_HISTORY,
                )
            }
    }
}

private class AndyHostEventSink(
    private val sessionId: String,
    private val historyStore: AndyCommandHistoryStore,
    private val sessionProvider: () -> KetraSession?,
    private val onWindowTitle: (String) -> Unit,
) : HostEventSink {
    override fun bell() = Unit

    override fun iconTitleChanged(title: String) {
        onWindowTitle(title)
    }

    override fun windowTitleChanged(title: String) {
        onWindowTitle(title)
    }

    override fun resizeWindow(rows: Int, columns: Int) = Unit

    override fun showNotification(title: String, body: String, level: NotificationLevel) {
        AndyDesktopNotificationManager.showNotification(title, body, level, sessionId = sessionId)
    }

    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
        if (event.marker != ShellIntegrationMarker.COMMAND_FINISHED) return
        val session = sessionProvider() ?: return
        val recordId = session.shellIntegrationState.latestCommandRecordId()
        val metadata = session.shellIntegrationState.commandMetadata(recordId) ?: return
        historyStore.record(profileId = sessionId, metadata = metadata)
    }
}
