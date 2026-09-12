package app.andy.ui.actions

import app.andy.model.ActionProject
import app.andy.service.RemoteProjectScanStatus
import app.andy.service.RemoteTargetProjects

/** Where a project lives, relative to the host currently driving the Projects list. */
sealed interface ProjectHost {
    /** This machine. Only appears as an "other" host while Andy is SSH-remoted. */
    data object Local : ProjectHost
    data class Ssh(val target: String) : ProjectHost
}

/**
 * A project that lives on a host other than the one currently driving the Projects list.
 *
 * These rows are display-only — chats, runbooks and worktrees all come from whichever host the
 * agent backend is attached to, so opening one switches Andy to [host] first and the project then
 * reappears as an ordinary entry once that host's `actions.toml` becomes the list.
 */
data class OtherHostProjectRow(
    val host: ProjectHost,
    val hostLabel: String,
    val project: ActionProject,
    val status: RemoteProjectScanStatus,
    val error: String? = null,
) {
    /**
     * List key. Project ids are only unique within one host — two machines with the same project
     * dir produce the same id — so the host has to be part of the key.
     */
    val key: String get() = when (host) {
        ProjectHost.Local -> "local::${project.id}"
        is ProjectHost.Ssh -> "${host.target}::${project.id}"
    }

    /** Last scan could not reach the host; [project] is what it looked like beforehand. */
    val stale: Boolean get() = status == RemoteProjectScanStatus.Unavailable
}

/**
 * Rows to append to the Projects list, host-grouped and alphabetical within a host.
 *
 * @param enabled `WorkspaceState.mergeRemoteProjects`; off means no extra rows at all.
 * @param activeTarget SSH target currently connected, whose projects are already the main list.
 * @param localProjects this machine's projects, which are only "elsewhere" while remoted — pass
 *   them empty when Andy is local, or they would duplicate the main list.
 * @param displayNameFor alias lookup, so rows read "m116" rather than "joe@192.168.4.29".
 */
fun otherHostProjectRows(
    enabled: Boolean,
    savedTargetProjects: Map<String, RemoteTargetProjects>,
    activeTarget: String?,
    localProjects: List<ActionProject> = emptyList(),
    localLabel: String = "This computer",
    displayNameFor: (String) -> String,
): List<OtherHostProjectRow> {
    if (!enabled) return emptyList()
    val local = if (activeTarget == null) {
        emptyList()
    } else {
        localProjects.sortedBy { it.name.lowercase() }.map { project ->
            OtherHostProjectRow(
                host = ProjectHost.Local,
                hostLabel = localLabel,
                project = project,
                status = RemoteProjectScanStatus.Ok,
            )
        }
    }
    val remote = savedTargetProjects.values
        .asSequence()
        .filter { it.target != activeTarget }
        .sortedBy { displayNameFor(it.target).lowercase() }
        .flatMap { host ->
            host.projects
                .sortedBy { it.name.lowercase() }
                .map { project ->
                    OtherHostProjectRow(
                        host = ProjectHost.Ssh(host.target),
                        hostLabel = displayNameFor(host.target),
                        project = project,
                        status = host.status,
                        error = host.error,
                    )
                }
        }
    // Home first — getting back to this machine is the most common hop.
    return local + remote
}

/**
 * Hosts that produced no rows but are still worth reporting — an unreachable laptop with nothing
 * cached would otherwise vanish silently, leaving the user wondering where its projects went.
 */
fun unreachableRemoteHosts(
    enabled: Boolean,
    savedTargetProjects: Map<String, RemoteTargetProjects>,
    activeTarget: String?,
    displayNameFor: (String) -> String,
): List<RemoteTargetProjects> {
    if (!enabled) return emptyList()
    return savedTargetProjects.values
        .filter {
            it.target != activeTarget &&
                it.projects.isEmpty() &&
                it.status == RemoteProjectScanStatus.Unavailable
        }
        .sortedBy { displayNameFor(it.target).lowercase() }
}
