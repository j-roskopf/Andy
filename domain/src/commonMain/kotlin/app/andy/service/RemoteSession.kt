package app.andy.service

import app.andy.model.ActionsConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection lifecycle for desktop SSH remote (andyd + tmux forwards). */
enum class RemoteSessionStatus {
    Local,
    Connecting,
    Connected,
    Error,
}

data class RemoteSessionState(
    val status: RemoteSessionStatus = RemoteSessionStatus.Local,
    /** SSH target string (`Host` alias or `user@host`) while connecting/connected/error. */
    val target: String? = null,
    val error: String? = null,
    /** Non-secret saved SSH targets from workspace prefs. */
    val savedTargets: List<String> = emptyList(),
    /**
     * Probed once per successful connect — Screen Sharing / VNC / screenshot tools on the
     * remote host. `null` while local or still connecting.
     */
    val hostCapabilities: RemoteHostCapabilities? = null,
) {
    val isRemote: Boolean get() = status == RemoteSessionStatus.Connected
}

/**
 * Desktop SSH remote control plane: tunnel `andyd.sock` (+ tmux) to another machine and
 * switch the GUI agent backend. Unavailable on web / unsupported platforms.
 */
interface RemoteSessionService {
    val state: StateFlow<RemoteSessionState>

    /**
     * Remote `~/.andy/actions.toml` projects while connected; `null` means use the local
     * [ActionConfigStore]. Chats live in andyd; project names/dirs live in this file.
     */
    val remoteActionsConfig: StateFlow<ActionsConfig?>

    /**
     * Active SSH local forwards: remote port → local port. Empty while local.
     * UI shows `8080 → 15001` only when the two numbers differ.
     */
    val portForwards: StateFlow<Map<Int, Int>>

    /** True while [RemoteSessionStatus.Connected] — local-only panes must stay hidden. */
    val isRemote: Boolean get() = state.value.isRemote

    suspend fun connect(target: String): Result<Unit>
    suspend fun disconnect()
    suspend fun reconnect(): Result<Unit>
    suspend fun addSavedTarget(target: String)
    suspend fun removeSavedTarget(target: String)
    /** Persist project edits to the remote host's `~/.andy/actions.toml` while remoted. */
    suspend fun saveRemoteActionsConfig(config: ActionsConfig): Result<Unit>

    /**
     * Lazily open an SSH local forward to [remotePort] on the connected host.
     * Returns the bound local port (same number when free, else an allocated fallback).
     */
    suspend fun forwardPort(remotePort: Int): Result<Int>

    /**
     * Forward the remote VNC port and launch the OS VNC client when Screen Sharing is
     * available. Returns a user-facing message on guidance / copy-URL / error paths.
     */
    suspend fun openRemoteScreen(): Result<String>
}

object UnavailableRemoteSessionService : RemoteSessionService {
    private val _state = MutableStateFlow(RemoteSessionState())
    override val state: StateFlow<RemoteSessionState> = _state.asStateFlow()
    override val remoteActionsConfig: StateFlow<ActionsConfig?> =
        MutableStateFlow<ActionsConfig?>(null).asStateFlow()
    override val portForwards: StateFlow<Map<Int, Int>> =
        MutableStateFlow<Map<Int, Int>>(emptyMap()).asStateFlow()

    override suspend fun connect(target: String): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun disconnect() = Unit

    override suspend fun reconnect(): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun addSavedTarget(target: String) = Unit

    override suspend fun removeSavedTarget(target: String) = Unit

    override suspend fun saveRemoteActionsConfig(config: ActionsConfig): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun forwardPort(remotePort: Int): Result<Int> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun openRemoteScreen(): Result<String> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))
}
