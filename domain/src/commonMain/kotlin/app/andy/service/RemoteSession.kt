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

/**
 * Active SSH ControlMaster coordinates for spawning an interactive shell on the
 * connected host (project Terminal docks). Paths are absolute on the *local* machine.
 */
data class RemoteShellEndpoint(
    val sshTarget: String,
    val controlPath: String,
)

data class RemoteSessionState(
    val status: RemoteSessionStatus = RemoteSessionStatus.Local,
    /** SSH target string (`Host` alias or `user@host`) while connecting/connected/error. */
    val target: String? = null,
    val error: String? = null,
    /** Non-secret saved SSH targets from workspace prefs (passwords optional via OS keychain). */
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

    /**
     * @param rememberPassword when true, persist the SSH password / passphrase used for this
     *   connect into the OS keychain (macOS Keychain / Linux Secret Service) for later reconnects.
     */
    suspend fun connect(target: String, rememberPassword: Boolean = false): Result<Unit>
    suspend fun disconnect()
    suspend fun reconnect(rememberPassword: Boolean = false): Result<Unit>
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

    /**
     * Live SSH mux for project Terminal docks while [isRemote]. `null` when local or
     * the tunnel is down — callers must fall back to a local shell only when null.
     */
    fun shellEndpoint(): RemoteShellEndpoint? = null
}

object UnavailableRemoteSessionService : RemoteSessionService {
    private val _state = MutableStateFlow(RemoteSessionState())
    override val state: StateFlow<RemoteSessionState> = _state.asStateFlow()
    override val remoteActionsConfig: StateFlow<ActionsConfig?> =
        MutableStateFlow<ActionsConfig?>(null).asStateFlow()
    override val portForwards: StateFlow<Map<Int, Int>> =
        MutableStateFlow<Map<Int, Int>>(emptyMap()).asStateFlow()

    override suspend fun connect(target: String, rememberPassword: Boolean): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun disconnect() = Unit

    override suspend fun reconnect(rememberPassword: Boolean): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun addSavedTarget(target: String) = Unit

    override suspend fun removeSavedTarget(target: String) = Unit

    override suspend fun saveRemoteActionsConfig(config: ActionsConfig): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun forwardPort(remotePort: Int): Result<Int> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun openRemoteScreen(): Result<String> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override fun shellEndpoint(): RemoteShellEndpoint? = null
}
