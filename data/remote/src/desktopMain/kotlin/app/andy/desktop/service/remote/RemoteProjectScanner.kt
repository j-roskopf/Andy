package app.andy.desktop.service.remote

import app.andy.desktop.service.DesktopActionConfigStore
import app.andy.model.ActionProject
import app.andy.model.CachedRemoteProject
import app.andy.service.RemoteProjectScanStatus
import app.andy.service.RemoteTargetProjects
import java.io.File
import java.util.concurrent.TimeUnit

/** Raw `ssh` outcome for one actions.toml read — [stdout] is the file body when [exitCode] is 0. */
data class RemoteTomlRead(val exitCode: Int, val stdout: String, val stderr: String)

private const val ReadActionsTomlCommand =
    "if [ -f \"\$HOME/.andy/actions.toml\" ]; then cat \"\$HOME/.andy/actions.toml\"; fi"

/**
 * Reads project definitions off saved SSH hosts for the merged Projects list *without* connecting
 * — no tunnel, no andyd, no backend swap. A host that is already warm or active passes its
 * ControlMaster path so the read reuses that authenticated mux; everything else goes over a
 * one-shot `BatchMode=yes` ssh that fails instead of prompting.
 */
class RemoteProjectScanner(
    private val read: (target: String, controlPath: File?) -> RemoteTomlRead = ::sshReadActionsToml,
) {
    fun scan(target: String, controlPath: File?): RemoteTargetProjects {
        val result = runCatching { read(target, controlPath) }
            .getOrElse { err ->
                return unavailable(target, err.message?.takeIf { it.isNotBlank() } ?: "ssh failed")
            }
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().lines()
                .firstOrNull { it.isNotBlank() }
                ?: "exit ${result.exitCode}"
            // A scan can only use credentials Andy already has. Say so, rather than leaving the
            // user to wonder why a host they can clearly connect to lists nothing.
            val hint = if (detail.contains("Permission denied", ignoreCase = true)) {
                " Connect to this host once (and tick Remember password) so scans can reuse it."
            } else {
                ""
            }
            return unavailable(target, detail + hint)
        }
        val text = result.stdout.trim()
        if (text.isEmpty()) {
            return RemoteTargetProjects(target, RemoteProjectScanStatus.Ok, emptyList())
        }
        return runCatching { DesktopActionConfigStore.parseToml(text) }
            .fold(
                onSuccess = { config ->
                    RemoteTargetProjects(target, RemoteProjectScanStatus.Ok, config.projects)
                },
                onFailure = { err ->
                    unavailable(target, "~/.andy/actions.toml is invalid: ${err.message ?: err}")
                },
            )
    }

    private fun unavailable(target: String, error: String) =
        RemoteTargetProjects(target, RemoteProjectScanStatus.Unavailable, emptyList(), error)

    companion object {
        /** Display-only projection persisted to workspace prefs so the list paints before SSH. */
        fun toCache(projects: List<ActionProject>): List<CachedRemoteProject> =
            projects.map { CachedRemoteProject(id = it.id, name = it.name, contextDir = it.contextDir) }

        fun fromCache(cached: List<CachedRemoteProject>): List<ActionProject> =
            cached.map { ActionProject(id = it.id, name = it.name, contextDir = it.contextDir) }

        /**
         * Initial flow value at launch: everything the last scan saw, marked
         * [RemoteProjectScanStatus.Cached] until a fresh scan confirms it. Targets the user has
         * since removed are dropped.
         */
        fun seedFromCache(
            cache: Map<String, List<CachedRemoteProject>>,
            savedTargets: List<String>,
        ): Map<String, RemoteTargetProjects> =
            savedTargets.mapNotNull { target ->
                val cached = cache[target] ?: return@mapNotNull null
                target to RemoteTargetProjects(
                    target = target,
                    status = RemoteProjectScanStatus.Cached,
                    projects = fromCache(cached),
                )
            }.toMap()

        /**
         * Cache to persist after a scan round. A target that came back unavailable keeps its
         * previous entry — a laptop being asleep shouldn't empty its projects out of the list.
         */
        fun updatedCache(
            previous: Map<String, List<CachedRemoteProject>>,
            scanned: Map<String, RemoteTargetProjects>,
            savedTargets: List<String>,
        ): Map<String, List<CachedRemoteProject>> =
            savedTargets.mapNotNull { target ->
                val fresh = scanned[target]
                val entry = if (fresh != null && fresh.status == RemoteProjectScanStatus.Ok) {
                    toCache(fresh.projects)
                } else {
                    previous[target]
                }
                entry?.let { target to it }
            }.toMap()
    }
}

/**
 * Reads the file over `ssh`, never prompting. Three tiers, best first:
 *
 * 1. A live ControlMaster (host is active or warm) — already authenticated.
 * 2. The password Andy saved in the OS keychain for this host, served through a probe-private
 *    [SshScanAskpass]. Hosts that authenticate with a password — which Andy explicitly supports —
 *    are unreachable any other way, since `BatchMode` rules password auth out.
 * 3. `BatchMode=yes`, which covers key-auth hosts and fails cleanly on everything else.
 */
fun sshReadActionsToml(target: String, controlPath: File?): RemoteTomlRead {
    val mux = controlPath?.takeIf { it.exists() }
    if (mux != null) return runActionsTomlRead(target, SshProcess.batchOptions(mux), emptyMap())

    val saved = runCatching { SshCredentialStore.load(target) }.getOrNull()
    if (!saved.isNullOrEmpty()) {
        SshScanAskpass.open(saved)?.use { askpass ->
            return runActionsTomlRead(
                target,
                SshProcess.credentialProbeOptions(null),
                askpass.environment,
            )
        }
    }
    return runActionsTomlRead(target, SshProcess.batchOptions(null), emptyMap())
}

private fun runActionsTomlRead(
    target: String,
    options: List<String>,
    env: Map<String, String>,
): RemoteTomlRead {
    val cmd = buildList {
        add("ssh")
        addAll(options)
        add(target)
        add(ReadActionsTomlCommand)
    }
    // Never the shared interactive broker — a scan must not be able to raise a dialog.
    val builder = SshProcess.processBuilder(cmd, askpass = false).redirectErrorStream(false)
    if (env.isNotEmpty()) {
        builder.environment().putAll(env)
        builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    }
    val process = builder.start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val finished = process.waitFor(20, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return RemoteTomlRead(-1, stdout, stderr.ifBlank { "timed out" })
    }
    return RemoteTomlRead(process.exitValue(), stdout, stderr)
}
