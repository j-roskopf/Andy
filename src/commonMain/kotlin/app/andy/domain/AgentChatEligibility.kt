package app.andy.domain

import app.andy.model.AgentTask

/** Recommended action for attaching new contextual evidence (§4/§5) to a chat. */
enum class AgentChatAttachAction {
    /** No suitable existing chat — start a brand new one instead. */
    NewTask,
    /** The chat is actively running; hold the context as a queued follow-up. */
    QueueFollowUp,
    /** The chat is idle or finished; resume its session with the new context. */
    Resume,
    /** The chat is waiting on a decision checkpoint and cannot take more context yet. */
    Blocked,
}

/** One existing chat considered for attaching contextual evidence, with Andy's recommended action. */
data class AgentChatEligibility(
    val taskId: String,
    val title: String,
    val projectId: String?,
    val action: AgentChatAttachAction,
    /** Set only when [action] is [AgentChatAttachAction.Blocked]. */
    val blockedReason: String? = null,
)

/**
 * Existing chats a contextual agent action (§4/§5) may attach evidence to: chats in the same
 * project as [projectId], plus inbox chats (no project) which stay visible regardless of which
 * project the action came from. Archived and not-yet-launched chats are never offered. Active
 * chats sort first, then most recently active.
 */
fun eligibleAgentChatsForContext(tasks: List<AgentTask>, projectId: String?): List<AgentChatEligibility> = tasks
    .asSequence()
    .filterNot { it.archived }
    .filterNot { it.isQueued }
    .filter { it.projectId == projectId || it.projectId == null }
    .sortedWith(
        compareByDescending<AgentTask> { it.isActive }
            .thenByDescending { it.latestActivityMillis() },
    )
    .map(::agentChatEligibility)
    .toList()

/** Andy's recommended action for attaching new contextual evidence to [task]. */
fun agentChatEligibility(task: AgentTask): AgentChatEligibility = AgentChatEligibility(
    taskId = task.id,
    title = task.title,
    projectId = task.projectId,
    action = task.recommendedAttachAction(),
    blockedReason = task.blockedAttachReason(),
)

private fun AgentTask.latestActivityMillis(): Long = finishedAtMillis ?: startedAtMillis ?: createdAtMillis

private fun AgentTask.recommendedAttachAction(): AgentChatAttachAction = when {
    needsInput -> AgentChatAttachAction.Blocked
    isActive -> AgentChatAttachAction.QueueFollowUp
    else -> AgentChatAttachAction.Resume
}

private fun AgentTask.blockedAttachReason(): String? = if (needsInput) {
    "Waiting on an answer to a decision checkpoint before it can take more context."
} else {
    null
}
