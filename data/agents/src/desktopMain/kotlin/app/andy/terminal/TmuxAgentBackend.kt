package app.andy.terminal

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
 * Headless agent executor: owns a detached `tmux -L andy` session.
 * No Swing widget — suitable for the daemon.
 */
class TmuxAgentBackend(
    override val sessionId: String,
) : TerminalSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private var scrapeJob: Job? = null
    private val killOnClose = AtomicBoolean(true)

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    private val _windowTitle = MutableStateFlow("")
    override val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _oscProgress = MutableStateFlow("")
    override val oscProgress: StateFlow<String> = _oscProgress.asStateFlow()

    /** Liveness as of the last scrape cycle, so [isAlive] does not fork per call. */
    @Volatile private var lastAlive: Boolean = true

    override val isAlive: Boolean
        get() = _exitCode.value == null &&
            if (scrapeJob?.isActive == true) lastAlive else TmuxAndy.hasSession(sessionId)

    override val pid: Long? get() = null

    /** When false, [close] detaches tracking only and leaves the tmux session running. */
    fun setKillOnClose(kill: Boolean) {
        killOnClose.set(kill)
    }

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        TmuxAndy.newSession(sessionId, cwd, argv, env)

        // One tmux fork per cycle covers buffer, title and liveness; exit detection rides
        // along rather than running a second `has-session` poller alongside it.
        scrapeJob = scope.launch {
            var last = ""
            while (isActive && _exitCode.value == null) {
                val probe = TmuxAndy.probePane(sessionId, historyLines = 80)
                lastAlive = probe.alive
                if (!probe.alive) break
                val snap = probe.content.trimEnd()
                if (snap != last) {
                    last = snap
                    _bufferSnapshots.emit(snap)
                }
                if (probe.title != _windowTitle.value) _windowTitle.value = probe.title
                delay(250)
            }
            if (!isActive) return@launch
            if (_exitCode.value == null) _exitCode.value = 0
        }
    }

    override fun write(bytes: ByteArray) {
        writeText(bytes.decodeToString())
    }

    override fun writeText(text: String) {
        if (!isAlive) return
        TmuxAndy.sendKeys(sessionId, text)
    }

    override fun resize(cols: Int, rows: Int) {
        // tmux size is negotiated by attached clients; headless executor has no client.
    }

    override fun bufferSnapshot(): String {
        val probe = TmuxAndy.probePane(sessionId, historyLines = 80)
        lastAlive = probe.alive
        if (!probe.alive) return ""
        return probe.content.trimEnd()
    }

    override fun close() {
        scrapeJob?.cancel()
        if (killOnClose.get()) {
            TmuxAndy.killSession(sessionId)
        }
        if (_exitCode.value == null && !TmuxAndy.hasSession(sessionId)) {
            _exitCode.value = 0
        }
        scope.cancel()
    }
}
