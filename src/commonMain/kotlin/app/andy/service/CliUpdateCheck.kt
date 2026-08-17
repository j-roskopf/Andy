package app.andy.service

import app.andy.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compares installed provider CLI versions ([app.andy.model.AgentCliStatus]) against
 * their latest published versions. Only providers with a known public package registry
 * are checkable; others are silently skipped rather than guessed at.
 */
interface CliUpdateCheckService {
    val outdated: StateFlow<List<CliUpdateInfo>>

    /** CLIs with a self-update currently running via [startUpdate]. */
    val updating: StateFlow<Set<AgentKind>>

    /** Probes installed CLIs for updates; implementations may throttle actual network checks. */
    suspend fun checkForUpdates()

    /** Hides [kind] from [outdated] until a newer version than [latestVersion] is published. */
    fun dismiss(kind: AgentKind, latestVersion: String)

    /**
     * Runs the CLI's own self-update command in a terminal run and returns the runId to dock,
     * or null if this platform can't run it. Andy re-probes the installed version in the
     * background afterward so [outdated] clears once the update actually lands.
     */
    fun startUpdate(item: CliUpdateInfo): String?
}

data class CliUpdateInfo(
    val kind: AgentKind,
    val installedVersion: String,
    val latestVersion: String,
    val binaryPath: String,
)

object UnavailableCliUpdateCheckService : CliUpdateCheckService {
    override val outdated: StateFlow<List<CliUpdateInfo>> = MutableStateFlow(emptyList())
    override val updating: StateFlow<Set<AgentKind>> = MutableStateFlow(emptySet())
    override suspend fun checkForUpdates() = Unit
    override fun dismiss(kind: AgentKind, latestVersion: String) = Unit
    override fun startUpdate(item: CliUpdateInfo): String? = null
}
