package app.andy.terminal

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPU-native Ghostty path (macOS/Linux). The C API is alpha — all churn stays
 * behind [TerminalSession]. Until `native/andy-terminal` ships a loadable lib,
 * [isAvailable] is false and the factory selects JediTerm.
 */
class LibghosttyBackend(
    override val sessionId: String,
) : TerminalSession {
    private val _exitCode = MutableStateFlow<Int?>(null)
    override val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    private val _bufferSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 1, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = _bufferSnapshots.asSharedFlow()

    override val isAlive: Boolean = false
    override val pid: Long? = null

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) {
        throw UnsupportedOperationException(
            "LibghosttyBackend is not linked yet — use JediTermBackend. " +
                "See native/andy-terminal/README.md.",
        )
    }

    override fun write(bytes: ByteArray) = Unit
    override fun resize(cols: Int, rows: Int) = Unit
    override fun bufferSnapshot(): String = ""
    override fun close() = Unit

    companion object {
        /** True when the native libghostty JNI bridge loads successfully. */
        fun isAvailable(): Boolean = false
    }
}
