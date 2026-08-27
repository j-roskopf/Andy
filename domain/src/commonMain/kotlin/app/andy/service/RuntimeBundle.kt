package app.andy.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Install/update the CLI, andyd, tmux, and related helpers under `~/.andy`
 * from the latest GitHub release (same assets as `install-andy.sh`).
 */
interface RuntimeBundleService {
    val state: StateFlow<RuntimeBundleState>

    /** Probe installed files and optionally fetch the latest release version. */
    suspend fun refresh(checkLatest: Boolean = true)

    /** Download and install CLI / andyd / tmux / hooks from the latest GitHub release. */
    suspend fun installOrUpdateFromLatest()
}

sealed interface RuntimeBundleState {
    data object Idle : RuntimeBundleState
    data object Checking : RuntimeBundleState
    data class Ready(val snapshot: RuntimeBundleSnapshot) : RuntimeBundleState
    data class Installing(
        val snapshot: RuntimeBundleSnapshot?,
        val message: String,
        val progress: Float? = null,
    ) : RuntimeBundleState
    data class Failed(
        val message: String,
        val snapshot: RuntimeBundleSnapshot? = null,
    ) : RuntimeBundleState
}

data class RuntimeBundleSnapshot(
    /** False on Windows — CLI/andyd require Unix sockets + tmux. */
    val platformSupported: Boolean,
    val installedReleaseVersion: String?,
    val latestReleaseVersion: String?,
    val latestReleasePageUrl: String?,
    val updateAvailable: Boolean,
    val components: List<RuntimeComponentStatus>,
    /** Non-null when `andy` is missing from PATH or points elsewhere. */
    val pathHint: String? = null,
    val andydRunning: Boolean = false,
)

data class RuntimeComponentStatus(
    val id: String,
    val label: String,
    val path: String?,
    val installed: Boolean,
    val detail: String? = null,
)

object UnavailableRuntimeBundleService : RuntimeBundleService {
    override val state = MutableStateFlow<RuntimeBundleState>(RuntimeBundleState.Idle)
    override suspend fun refresh(checkLatest: Boolean) = Unit
    override suspend fun installOrUpdateFromLatest() = Unit
}
