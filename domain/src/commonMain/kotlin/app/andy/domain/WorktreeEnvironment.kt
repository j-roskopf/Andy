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
 * A task that owns or reuses a worktree resolves to itself. Otherwise the task is matched to a
 * same-project sibling whose worktree path equals its [AgentTask.cwd] — the shared-worktree case
 * for handoff and side chats. When several tasks share the path the owning task wins, then the
 * oldest, mirroring the worktree tree's owner preference.
 */
fun resolveWorktreeEnvironment(task: AgentTask, tasks: List<AgentTask>): ResolvedWorktree? {
    task.worktreePath?.takeIf { it.isNotBlank() }?.let { path ->
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
        .filter { it.worktreePath?.takeIf(String::isNotBlank)?.let { path -> normalizeDir(path) == needle } == true }
        .minWithOrNull(compareByDescending<AgentTask> { it.ownsWorktree }.thenBy { it.createdAtMillis })
        ?: return null
    return ResolvedWorktree(
        path = host.worktreePath!!,
        branchName = host.branchName?.takeIf { it.isNotBlank() },
        ownerTaskId = host.id,
        mergeTargetDir = mergeTargetDir(host, tasks),
    )
}

/** Merge into the parent worktree when the branch was forked from one, else the repo root. */
private fun mergeTargetDir(task: AgentTask, tasks: List<AgentTask>): String? {
    val parentPath = task.parentWorktreeTaskId
        ?.let { id -> tasks.firstOrNull { it.id == id }?.worktreePath }
        ?.takeIf { it.isNotBlank() }
    return parentPath ?: task.originDir?.takeIf { it.isNotBlank() }
}

/** Cheap normalization for path comparison: only trailing separators differ in practice. */
private fun normalizeDir(path: String): String = path.trimEnd('/')
