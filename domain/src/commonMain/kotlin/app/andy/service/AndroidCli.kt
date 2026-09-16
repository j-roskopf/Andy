package app.andy.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Installs and manages Google's official Android CLI and the Android skills it
 * distributes (https://developer.android.com/tools/agents/android-cli and
 * https://developer.android.com/tools/agents/android-skills).
 *
 * The CLI is a self-contained binary; after it is on the machine skills are
 * installed through `android skills add`, which drops them into the skill
 * directories of every detected agent (Claude Code, Codex, Cursor, …).
 */
interface AndroidCliService {
    val state: StateFlow<AndroidCliState>

    /** Probe the CLI install and refresh the available/installed skill catalog. */
    suspend fun refresh()

    /** Download the Android CLI for this platform and install it under `~/.local/bin`. */
    suspend fun installCli()

    /** Install or update every Android skill via `android skills add --all`. */
    suspend fun installAllSkills()

    /** Install or update a single Android skill by catalog name. */
    suspend fun installSkill(skillName: String)
}

sealed interface AndroidCliState {
    data object Idle : AndroidCliState
    data object Checking : AndroidCliState
    data class Ready(val snapshot: AndroidCliSnapshot) : AndroidCliState
    data class Busy(
        val snapshot: AndroidCliSnapshot?,
        val message: String,
        val progress: Float? = null,
        /** Catalog name of the skill currently installing, when a single skill is targeted. */
        val activeSkill: String? = null,
        /** The exact command being run, shown in the panel console. */
        val command: String? = null,
        /** Most recent combined stdout/stderr lines from [command]. */
        val log: List<String> = emptyList(),
    ) : AndroidCliState
    data class Failed(
        val message: String,
        val snapshot: AndroidCliSnapshot? = null,
        /** Command that failed, when the failure came from running one. */
        val command: String? = null,
        /** Output captured before the failure. */
        val log: List<String> = emptyList(),
    ) : AndroidCliState
}

data class AndroidCliSnapshot(
    /** False on platforms the CLI does not ship a binary for. */
    val platformSupported: Boolean,
    /** e.g. `darwin_arm64`; null when [platformSupported] is false. */
    val installTarget: String?,
    val cliInstalled: Boolean,
    val cliPath: String?,
    val availableSkills: List<AndroidSkillCatalogEntry>,
    val installedSkills: Set<String>,
    /** Set when the skill catalog could not be fetched (offline, rate limited, …). */
    val catalogError: String? = null,
    /** Non-null when `android` is installed somewhere that is not on `PATH`. */
    val pathHint: String? = null,
)

data class AndroidSkillCatalogEntry(
    val name: String,
    val description: String?,
    /** Top-level category directory in the android/skills repo, e.g. `navigation`. */
    val category: String?,
)

object UnavailableAndroidCliService : AndroidCliService {
    override val state = MutableStateFlow<AndroidCliState>(AndroidCliState.Idle)
    override suspend fun refresh() = Unit
    override suspend fun installCli() = Unit
    override suspend fun installAllSkills() = Unit
    override suspend fun installSkill(skillName: String) = Unit
}
