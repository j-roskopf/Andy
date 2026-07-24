package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
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
import javax.swing.SwingUtilities

/**
 * Cross-platform PTY session backed by JediTerm + pty4j.
 * Same stack Actions already uses for project terminals.
 */
class JediTermBackend(
    override val sessionId: String,
    private val cols: Int = 120,
    private val rows: Int = 32,
    private val appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
) : TerminalSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var process: PtyProcess? = null
    @Volatile private var terminal: JediTermWidget? = null
    private var waitJob: Job? = null
    private var scrapeJob: Job? = null

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    override val isAlive: Boolean
        get() = process?.isAlive == true

    override val pid: Long?
        get() = process?.pid()?.takeIf { it > 0 }

    fun terminalWidget(): JediTermWidget? = terminal

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        require(argv.isNotEmpty()) { "argv must not be empty" }

        // Merge caller overrides onto the process environment, then scrub IDE/proxy
        // vars. Scrub must run AFTER putAll — otherwise Cursor-injected NODE_OPTIONS
        // (js-debug bootloader) survives and makes Claude Code exit 1 immediately.
        val environment = HashMap(System.getenv()).apply {
            putAll(env)
            scrubInheritedTerminalEnvironment(this)
            put("TERM", "xterm-256color")
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
        val connector = PtyTtyConnector(pty, argv)
        terminal = onSwingEdt {
            createAndyJediTermWidget(cols, rows, appearance).apply {
                ttyConnector = connector
                start()
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
        val connector = terminal?.ttyConnector ?: return
        onSwingEdt {
            runCatching { connector.write(bytes) }
        }
    }

    override fun writeText(text: String) {
        val connector = terminal?.ttyConnector ?: return
        // Match Actions: String write on the EDT so JediTerm's connector flushes correctly.
        onSwingEdt {
            runCatching { connector.write(text) }
        }
    }

    override fun resize(cols: Int, rows: Int) {
        val widget = terminal ?: return
        onSwingEdt {
            widget.terminal.resize(TermSize(cols, rows), com.jediterm.terminal.RequestOrigin.User)
        }
    }

    override fun bufferSnapshot(): String {
        val widget = terminal ?: return ""
        return runCatching {
            widget.terminalTextBuffer.getScreenLines().trimEnd()
        }.getOrDefault("")
    }

    /**
     * History + screen as newline-oriented ANSI for durable scrollback files.
     * Safe to call from any thread; runs on the Swing EDT.
     */
    fun exportScrollbackAnsi(): String {
        val widget = terminal ?: return ""
        return runCatching {
            onSwingEdt { widget.terminalTextBuffer.exportScrollbackAnsi() }
        }.getOrDefault("")
    }

    override fun close() {
        scrapeJob?.cancel()
        waitJob?.cancel()
        terminal?.let { widget -> onSwingEdt { widget.close() } }
        process?.let { pty ->
            if (pty.isAlive) {
                pty.destroy()
                if (!pty.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    pty.destroyForcibly()
                }
            }
        }
        terminal = null
        process = null
        scope.cancel()
    }
}

private class PtyTtyConnector(
    private val process: PtyProcess,
    commandLine: List<String>,
) : ProcessTtyConnector(process, StandardCharsets.UTF_8, commandLine) {
    override fun resize(termSize: TermSize) {
        if (isConnected) process.setWinSize(WinSize(termSize.columns, termSize.rows))
    }

    override fun isConnected(): Boolean = process.isAlive

    override fun getName(): String = "Local"
}

internal fun <T> onSwingEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
