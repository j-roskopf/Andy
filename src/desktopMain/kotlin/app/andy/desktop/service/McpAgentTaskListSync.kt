package app.andy.desktop.service

import app.andy.model.AgentTask

/**
 * Merge a daemon [refreshed] chat list with local UI read state.
 *
 * [clientReadTaskIds] tracks chats the user opened before the daemon has confirmed
 * [AgentTask.unread] = false. [viewingTaskIds] is the currently open chat.
 */
internal fun mergeRefreshedAgentTasks(
    refreshed: List<AgentTask>,
    clientReadTaskIds: Set<String>,
    viewingTaskIds: Set<String>,
): List<AgentTask> =
    refreshed.map { task ->
        when {
            task.id in clientReadTaskIds && task.unread -> task.copy(unread = false)
            task.id in viewingTaskIds && task.unread -> task.copy(unread = false)
            else -> task
        }
    }

/** Drop ids once the daemon list agrees the chat is read. */
internal fun dropConfirmedClientReads(
    clientReadTaskIds: MutableSet<String>,
    refreshed: List<AgentTask>,
) {
    clientReadTaskIds.removeIf { id -> refreshed.any { it.id == id && !it.unread } }
}
