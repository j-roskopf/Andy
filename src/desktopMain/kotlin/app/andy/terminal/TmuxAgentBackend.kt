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
    private var waitJob: Job? = null
    private var scrapeJob: Job? = null
    private val killOnClose = AtomicBoolean(true)

    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    override val isAlive: Boolean
        get() = _exitCode.value == null && TmuxAndy.hasSession(sessionId)

    override val pid: Long? get() = null

    /** When false, [close] detaches tracking only and leaves the tmux session running. */
    fun setKillOnClose(kill: Boolean) {
        killOnClose.set(kill)
    }

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        check(started.compareAndSet(false, true)) { "TerminalSession already started" }
        TmuxAndy.newSession(sessionId, cwd, argv, env)

        waitJob = scope.launch {
            val code = TmuxAndy.waitExit(sessionId)
            _exitCode.value = code
            scrapeJob?.cancel()
        }
        scrapeJob = scope.launch {
            var last = ""
            while (isActive && _exitCode.value == null) {
                if (!TmuxAndy.hasSession(sessionId)) break
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
        if (!TmuxAndy.hasSession(sessionId)) return ""
        return TmuxAndy.capturePane(sessionId, historyLines = 80).trimEnd()
    }

    override fun close() {
        scrapeJob?.cancel()
        waitJob?.cancel()
        if (killOnClose.get()) {
            TmuxAndy.killSession(sessionId)
        }
        if (_exitCode.value == null && !TmuxAndy.hasSession(sessionId)) {
            _exitCode.value = 0
        }
        scope.cancel()
    }
}
