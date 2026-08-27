package app.andy.service

import kotlinx.coroutines.flow.StateFlow

interface AppUpdateService {
    val state: StateFlow<AppUpdateState>
    val pendingInstallConfirmation: StateFlow<AvailableUpdate?>

    suspend fun checkForUpdates(onFailure: (Throwable) -> Unit = {})
    suspend fun installAvailableUpdate(onMessage: (String) -> Unit = {})
    fun respondToInstallConfirmation(install: Boolean)
}

sealed interface AppUpdateState {
    object Idle : AppUpdateState
    object Checking : AppUpdateState
    object Current : AppUpdateState
    data class Available(val update: AvailableUpdate) : AppUpdateState
    data class Installing(
        val update: AvailableUpdate,
        val message: String,
        val progress: Float? = null,
    ) : AppUpdateState
    data class Failed(val message: String, val lastKnownUpdate: AvailableUpdate? = null) : AppUpdateState
}

data class AvailableUpdate(
    val versionName: String,
    val releaseName: String?,
    val releaseNotes: String?,
    val releasePageUrl: String,
    val asset: ReleaseAsset?,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256Digest: String?,
)

data class UpdateInstallProgress(
    val phase: UpdateInstallPhase,
    val message: String,
    val fraction: Float? = null,
)

enum class UpdateInstallPhase {
    Downloading,
    ReadyToInstall,
    Installing,
}

enum class UpdatePlatform {
    Android,
    Ios,
    MacOs,
    Windows,
    LinuxDeb,
    LinuxFlatpak,
    Web,
    Other,
}
