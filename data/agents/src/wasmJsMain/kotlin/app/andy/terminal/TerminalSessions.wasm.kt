package app.andy.terminal

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class StubTerminalSession(override val sessionId: String) : TerminalSession {
    override val isAlive: Boolean = false
    override val exitCode: StateFlow<Int?> = MutableStateFlow(null).asStateFlow()
    override val pid: Long? = null
    override val bufferSnapshots: SharedFlow<String> = MutableSharedFlow()
    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) = Unit
    override fun write(bytes: ByteArray) = Unit
    override fun resize(cols: Int, rows: Int) = Unit
    override fun bufferSnapshot(): String = ""
    override fun close() = Unit
}

actual object TerminalSessions {
    actual fun create(request: TerminalLaunchRequest): TerminalSession = StubTerminalSession(request.sessionId)
}
