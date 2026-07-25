package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import io.github.ketraterm.ui.swing.api.SwingTerminal
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GUI viewer: attaches KetraTerm/Pty4J to an existing `tmux -L andy` session.
 *
 * The agent process is owned by the detached tmux session (created by
 * [TmuxAndy.newSession] or [TmuxAgentBackend]); this backend only provides the
 * Swing widget and local keystroke path.
 */
class TmuxAttachBackend(
    override val sessionId: String,
    cols: Int = 120,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
    private val killTmuxOnClose: Boolean = false,
) : TerminalSession {
    private val inner = KetraTermBackend(
        sessionId = sessionId,
        cols = cols,
        rows = rows,
        appearance = appearance,
    )

    override val isAlive: Boolean
        get() = inner.isAlive || TmuxAndy.hasSession(sessionId)

    override val exitCode: StateFlow<Int?> get() = inner.exitCode
    override val pid: Long? get() = inner.pid
    override val bufferSnapshots: SharedFlow<String> get() = inner.bufferSnapshots

    fun swingTerminal(): SwingTerminal? = inner.swingTerminal()

    fun scrollbackAnsi(): String = inner.scrollbackAnsi()

    fun captureReadableLines(seenKeys: MutableSet<String>): List<String> =
        inner.captureReadableLines(seenKeys)

    fun updateAppearance(appearance: TerminalAppearanceSnapshot) = inner.updateAppearance(appearance)

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        // argv/cwd/env from callers are ignored — we always attach to the Andy tmux session.
        check(TmuxAndy.hasSession(sessionId)) {
            "tmux session ${TmuxAndy.sessionName(sessionId)} does not exist; create it before attaching"
        }
        inner.start(TmuxAndy.attachArgv(sessionId), cwd = null, env = emptyMap())
    }

    /** Attach to an already-running tmux session (preferred entry for GUI). */
    fun attach() {
        start(emptyList(), cwd = null, env = emptyMap())
    }

    override fun write(bytes: ByteArray) = inner.write(bytes)

    override fun writeText(text: String) = inner.writeText(text)

    override fun resize(cols: Int, rows: Int) = inner.resize(cols, rows)

    override fun bufferSnapshot(): String = inner.bufferSnapshot()

    override fun close() {
        inner.close()
        if (killTmuxOnClose) {
            TmuxAndy.killSession(sessionId)
        }
    }
}
