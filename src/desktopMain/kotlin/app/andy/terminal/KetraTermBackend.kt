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
        appearance.toSwingSettings(columns = cols, rows = rows, scrollbackLines = DEFAULT_MAX_HISTORY),
    )
    private val scrollbackTee = ScrollbackAnsiTee()
    private val historyStore by lazy { AndyCommandHistoryStore.shared() }

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    override val isAlive: Boolean
        get() = process?.isAlive == true

    override val pid: Long?
        get() = process?.pid()?.takeIf { it > 0 }

    fun swingTerminal(): SwingTerminal? = swingTerminal

    /** Raw teed PTY stdout for durable `scrollback.ansi` persistence. */
    fun scrollbackAnsi(): String = scrollbackTee.snapshot()

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) {
        appearanceRef.set(appearance)
        val settings = appearance.toSwingSettings(
            columns = cols,
            rows = rows,
            scrollbackLines = DEFAULT_MAX_HISTORY,
        )
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
            .setDirectory(cwd)
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
        val tee = TeeTerminalConnector(connector, scrollbackTee)
        val buffer = TerminalBuffers.create(
            width = cols,
            height = rows,
            maxHistory = DEFAULT_MAX_HISTORY,
        )
        val hostSink = AndyHostEventSink(
            sessionId = sessionId,
            historyStore = historyStore,
            sessionProvider = { ketraSession },
        )
        val session = KetraSession.create(
            terminal = buffer,
            connector = tee,
            hostEvents = hostSink,
            hostPolicy = HostPolicy(notificationPolicy = HostControlPolicy.ALLOW),
            inputPolicy = PtyOptions.defaultInputPolicy(),
        )
        ketraSession = session
        session.start(cols, rows)

        swingTerminal = onSwingEdt {
            SwingTerminal(
                settingsProvider = { settingsRef.get() },
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
            while (isActive && pty.isAlive) {
                val snap = bufferSnapshot()
                if (snap != last) {
                    last = snap
                    _bufferSnapshots.emit(snap)
                }
                delay(250)
            }
            val finalSnap = bufferSnapshot()
            if (finalSnap != last) _bufferSnapshots.emit(finalSnap)
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
        onSwingEdt {
            runCatching { session.resize(cols, rows) }
        }
    }

    override fun bufferSnapshot(): String {
        val session = ketraSession ?: return ""
        return runCatching { session.terminal.getScreenAsString().trimEnd() }.getOrDefault("")
    }

    override fun close() {
        scrapeJob?.cancel()
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
        swingTerminal = null
        ketraSession = null
        ptyConnector = null
        process = null
        scope.cancel()
    }

    companion object {
        /** Scrollback lines retained by the emulator (~5MB ANSI soft-cap philosophy). */
        const val DEFAULT_MAX_HISTORY: Int = 10_000
    }
}

private class AndyHostEventSink(
    private val sessionId: String,
    private val historyStore: AndyCommandHistoryStore,
    private val sessionProvider: () -> KetraSession?,
) : HostEventSink {
    override fun bell() = Unit

    override fun iconTitleChanged(title: String) = Unit

    override fun windowTitleChanged(title: String) = Unit

    override fun resizeWindow(rows: Int, columns: Int) = Unit

    override fun showNotification(title: String, body: String, level: NotificationLevel) {
        AndyDesktopNotificationManager.showNotification(title, body, level)
    }

    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
        if (event.marker != ShellIntegrationMarker.COMMAND_FINISHED) return
        val session = sessionProvider() ?: return
        val recordId = session.shellIntegrationState.latestCommandRecordId()
        val metadata = session.shellIntegrationState.commandMetadata(recordId) ?: return
        historyStore.record(profileId = sessionId, metadata = metadata)
    }
}
