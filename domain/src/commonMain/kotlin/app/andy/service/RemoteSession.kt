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

    /** True while [RemoteSessionStatus.Connected] — local-only panes must stay hidden. */
    val isRemote: Boolean get() = state.value.isRemote

    suspend fun connect(target: String): Result<Unit>
    suspend fun disconnect()
    suspend fun reconnect(): Result<Unit>
    suspend fun addSavedTarget(target: String)
    suspend fun removeSavedTarget(target: String)
    /** Persist project edits to the remote host's `~/.andy/actions.toml` while remoted. */
    suspend fun saveRemoteActionsConfig(config: ActionsConfig): Result<Unit>
}

object UnavailableRemoteSessionService : RemoteSessionService {
    private val _state = MutableStateFlow(RemoteSessionState())
    override val state: StateFlow<RemoteSessionState> = _state.asStateFlow()
    override val remoteActionsConfig: StateFlow<ActionsConfig?> =
        MutableStateFlow<ActionsConfig?>(null).asStateFlow()

    override suspend fun connect(target: String): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun disconnect() = Unit

    override suspend fun reconnect(): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))

    override suspend fun addSavedTarget(target: String) = Unit

    override suspend fun removeSavedTarget(target: String) = Unit

    override suspend fun saveRemoteActionsConfig(config: ActionsConfig): Result<Unit> =
        Result.failure(IllegalStateException("SSH remote requires Andy Desktop on macOS or Linux."))
}
