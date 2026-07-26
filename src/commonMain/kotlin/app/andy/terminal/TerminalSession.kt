package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backend-agnostic PTY + emulator seam.
 *
 * Desktop uses KetraTerm (Pty4J under the hood). Wasm remains a no-op stub.
 */
interface TerminalSession {
    /** Opaque id for UI/host lookup (usually the agent task id). */
    val sessionId: String

    val isAlive: Boolean
    val exitCode: StateFlow<Int?>
    val pid: Long?

    /**
     * Debounced text snapshots of the visible terminal buffer (for scrape status).
     *
     * Implementations must emit whenever the visible screen changes: this is the only
     * channel status detection reads from. [bufferSnapshot] is for callers that need the
     * screen at a specific moment, and on tmux-backed sessions it can cost a subprocess,
     * so it must not be used as a substitute for observing this flow.
     */
    val bufferSnapshots: SharedFlow<String>

    /**
     * Latest OSC 0/2 window/icon title (empty when unsupported or unset).
     * Used by agent status screen manifests (Claude braille spinner, Codex action required, …).
     */
    val windowTitle: StateFlow<String>
        get() = EmptyOscTitle

    /**
     * Latest ConEmu-style OSC progress payload (`4;0`, `4;1`, …), empty when unset.
     */
    val oscProgress: StateFlow<String>
        get() = EmptyOscProgress

    fun start(argv: List<String>, cwd: String?, env: Map<String, String>)
    fun write(bytes: ByteArray)
    fun writeText(text: String) = write(text.encodeToByteArray())
    fun resize(cols: Int, rows: Int)
    fun bufferSnapshot(): String
    fun close()
}

/** How the desktop terminal backend should host the process. */
enum class TerminalMode {
    /** Direct Pty4J spawn (legacy / Actions shell). */
    DirectPty,

    /** Detached `tmux -L andy` session — headless daemon executor. */
    TmuxAgent,

    /** Attach KetraTerm to an existing `tmux -L andy` session for GUI. */
    TmuxAttach,
}

data class TerminalLaunchRequest(
    val sessionId: String,
    val argv: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val cols: Int = 120,
    val rows: Int = 32,
    val appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
    val mode: TerminalMode = TerminalMode.DirectPty,
    /** When [mode] is [TerminalMode.TmuxAttach], kill the tmux session on close. */
    val killTmuxOnClose: Boolean = false,
)

/** Platform factory — desktop creates a real PTY session; other targets are stubs. */
expect object TerminalSessions {
    fun create(request: TerminalLaunchRequest): TerminalSession
}

private val EmptyOscTitle: StateFlow<String> = MutableStateFlow("").asStateFlow()
private val EmptyOscProgress: StateFlow<String> = MutableStateFlow("").asStateFlow()
