package app.andy.terminal

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

actual object TerminalSessions {
    actual fun create(request: TerminalLaunchRequest): TerminalSession = StubTerminalSession(request.sessionId)
}

private class StubTerminalSession(
    override val sessionId: String,
) : TerminalSession {
    private val _exitCode = MutableStateFlow<Int?>(-1)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()
    private val _bufferSnapshots = MutableSharedFlow<String>(replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()
    override val isAlive: Boolean = false
    override val pid: Long? = null
    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) = Unit
    override fun write(bytes: ByteArray) = Unit
    override fun resize(cols: Int, rows: Int) = Unit
    override fun bufferSnapshot(): String = ""
    override fun close() = Unit
}
