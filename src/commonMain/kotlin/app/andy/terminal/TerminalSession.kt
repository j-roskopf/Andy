package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend-agnostic PTY + emulator seam.
 *
 * JediTerm (pty4j) ships everywhere; libghostty is the GPU-native macOS/Linux
 * path when the native library loads. The rest of Andy talks only to this.
 */
interface TerminalSession {
    /** Opaque id for UI/host lookup (usually the agent task id). */
    val sessionId: String

    val isAlive: Boolean
    val exitCode: StateFlow<Int?>
    val pid: Long?

    /** Debounced text snapshots of the visible terminal buffer (for scrape status). */
    val bufferSnapshots: SharedFlow<String>

    fun start(argv: List<String>, cwd: String?, env: Map<String, String>)
    fun write(bytes: ByteArray)
    fun writeText(text: String) = write(text.encodeToByteArray())
    fun resize(cols: Int, rows: Int)
    fun bufferSnapshot(): String
    fun close()
}

enum class TerminalBackendKind {
    JediTerm,
    Libghostty,
}

data class TerminalLaunchRequest(
    val sessionId: String,
    val argv: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val cols: Int = 120,
    val rows: Int = 32,
    val appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
)

/** Platform factory — desktop creates a real PTY session; other targets are stubs. */
expect object TerminalSessions {
    fun preferredBackend(): TerminalBackendKind
    fun create(request: TerminalLaunchRequest): TerminalSession
}
