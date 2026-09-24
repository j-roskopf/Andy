package app.andy.domain

import app.andy.model.AgentTask

/**
 * The worktree a chat is operating in, resolved from the live task list.
 *
 * A chat can work in a worktree it does not own: a handoff or side chat opened from a worktree
 * chat inherits the parent's directory as its [AgentTask.cwd] without a [AgentTask.worktreePath]
 * of its own. The Environment panel describes the directory the agent is editing, so those chats
 * resolve to the owning task's worktree rather than showing nothing.
 */
data class ResolvedWorktree(
    /** Worktree directory the chat works in. */
    val path: String,
    /** Branch checked out in [path], when known. */
    val branchName: String?,
    /** Task used for worktree-scoped queries such as the diff summary. */
    val ownerTaskId: String,
    /** Merge target for [branchName] — the parent worktree or the owner's repo root. */
    val mergeTargetDir: String?,
)

/**
 * Resolve the worktree [task] is working in, or null when it works outside any worktree.
 *
 * A task that owns or reuses an isolated worktree resolves to itself. Otherwise the task is
 * matched to a same-project sibling whose isolated worktree path equals its [AgentTask.cwd] —
 * the shared-worktree case for handoff and side chats. When several tasks share the path the
 * owning task wins, then the oldest, mirroring the worktree tree's owner preference.
 *
 * Paths that merely stamp the project root as [AgentTask.worktreePath] (workflow follow-ups that
 * reused the main checkout) are not treated as worktrees, so ordinary chats in that directory
 * do not inherit the Environment panel.
 */
fun resolveWorktreeEnvironment(task: AgentTask, tasks: List<AgentTask>): ResolvedWorktree? {
    task.takeIf { it.hasIsolatedWorktreePath() }?.worktreePath?.let { path ->
        return ResolvedWorktree(
            path = path,
            branchName = task.branchName?.takeIf { it.isNotBlank() },
            ownerTaskId = task.id,
            mergeTargetDir = mergeTargetDir(task, tasks),
        )
    }
    val cwd = task.cwd?.takeIf { it.isNotBlank() } ?: return null
    val needle = normalizeDir(cwd)
    val host = tasks
        .asSequence()
        .filter { it.id != task.id && it.projectId == task.projectId }
        .filter { it.hasIsolatedWorktreePath() }
        .filter { normalizeDir(it.worktreePath!!) == needle }
        .minWithOrNull(compareByDescending<AgentTask> { it.ownsWorktree }.thenBy { it.createdAtMillis })
        ?: return null
    return ResolvedWorktree(
        path = host.worktreePath!!,
        branchName = host.branchName?.takeIf { it.isNotBlank() },
        ownerTaskId = host.id,
        mergeTargetDir = mergeTargetDir(host, tasks),
    )
}

/**
 * True when [AgentTask.worktreePath] is an isolated checkout, not the project root reused as a
 * worktree label.
 *
 * Owners and reusable checkouts outside [AgentTask.originDir] always count. When origin was set
 * to the worktree itself (some handoffs), Andy-managed `~/.andy/worktrees/` paths still count.
 */
internal fun AgentTask.hasIsolatedWorktreePath(): Boolean {
    val path = worktreePath?.takeIf { it.isNotBlank() } ?: return false
    if (ownsWorktree) return true
    val origin = originDir?.takeIf { it.isNotBlank() } ?: return true
    if (normalizeDir(path) != normalizeDir(origin)) return true
    return isAndyManagedWorktreePath(path)
}

/** Andy creates isolated checkouts under `~/.andy/worktrees/<repo>-<id>`. */
internal fun isAndyManagedWorktreePath(path: String): Boolean =
    path.replace('\\', '/').contains("/.andy/worktrees/")

/** Merge into the parent worktree when the branch was forked from one, else the repo root. */
private fun mergeTargetDir(task: AgentTask, tasks: List<AgentTask>): String? {
    val parentPath = task.parentWorktreeTaskId
        ?.let { id -> tasks.firstOrNull { it.id == id }?.worktreePath }
        ?.takeIf { it.isNotBlank() }
    return parentPath ?: task.originDir?.takeIf { it.isNotBlank() }
}

/** Cheap normalization for path comparison: only trailing separators differ in practice. */
private fun normalizeDir(path: String): String = path.trimEnd('/')
