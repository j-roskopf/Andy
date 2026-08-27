package app.andy.domain

import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.time.Clock

/** Failed chats drop out of the priority section after this window unless they are still unread. */
const val RecentFailedChatTtlMillis = 24L * 60 * 60 * 1000

private fun clockNowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Working, blocked, launching, unread, or recently failed chats that deserve a pinned inbox section. */
fun AgentTask.isPriorityChat(nowMillis: Long = clockNowMillis()): Boolean =
    unread || isActive || isQueued || isRecentFailedChat(nowMillis)

fun AgentTask.isRecentFailedChat(nowMillis: Long = clockNowMillis()): Boolean {
    if (status != AgentStatus.Error) return false
    val failedAt = finishedAtMillis ?: startedAtMillis ?: createdAtMillis
    return nowMillis - failedAt <= RecentFailedChatTtlMillis
}

data class PriorityChatLists(
    val priority: List<AgentTask>,
    val rest: List<AgentTask>,
)

private val PriorityChatOrder = compareBy<AgentTask> { task ->
    when {
        task.needsInput -> 0
        task.isActive || task.isQueued -> 1
        task.status == AgentStatus.Error -> 2
        else -> 3
    }
}.thenByDescending { it.createdAtMillis }

fun splitPriorityChats(
    tasks: List<AgentTask>,
    nowMillis: Long = clockNowMillis(),
): PriorityChatLists {
    val priority = mutableListOf<AgentTask>()
    val rest = mutableListOf<AgentTask>()
    for (task in tasks) {
        if (task.isPriorityChat(nowMillis)) priority += task else rest += task
    }
    return PriorityChatLists(
        priority = priority.sortedWith(PriorityChatOrder),
        rest = rest,
    )
}

/**
 * Visible slice of a project chat list. When [pinPriority] is on, every priority chat
 * stays visible (they do not consume [limit]); [limit] applies only to non-priority recents.
 */
fun visibleChatSessions(
    sessions: List<AgentTask>,
    pinPriority: Boolean,
    expanded: Boolean,
    limit: Int,
    nowMillis: Long = clockNowMillis(),
): List<AgentTask> {
    if (!pinPriority) return if (expanded) sessions else sessions.take(limit)
    val split = splitPriorityChats(sessions, nowMillis)
    if (expanded) return split.priority + split.rest
    return split.priority + split.rest.take(limit)
}
