package app.andy.desktop.service

import app.andy.model.AgentTask

/**
 * Merge a daemon [refreshed] chat list with local UI read state.
 *
 * [clientReadTaskIds] tracks chats the user opened before the daemon has confirmed
 * [AgentTask.unread] = false. [viewingTaskIds] is the currently open chat.
 */
fun mergeRefreshedAgentTasks(
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
fun dropConfirmedClientReads(
    clientReadTaskIds: MutableSet<String>,
    refreshed: List<AgentTask>,
) {
    clientReadTaskIds.removeIf { id -> refreshed.any { it.id == id && !it.unread } }
}

/**
 * Retire local read acks the daemon has already applied.
 *
 * [settled] holds ids whose `chat.mark_read` RPC completed before the list currently being
 * merged was requested, so that list already reflects the read. Any `unread = true` it still
 * reports is therefore a *newer* transition — a turn that finished after the user opened the
 * chat — and must not be masked by the ack. Without this, an ack placed moments before the
 * turn completes is never confirmed (the daemon never reports the chat read again) and the
 * badge stays suppressed for the rest of the session.
 */
fun dropSettledClientReads(
    clientReadTaskIds: MutableSet<String>,
    daemonAckedReadTaskIds: MutableSet<String>,
    settled: Set<String>,
) {
    clientReadTaskIds.removeAll(settled)
    daemonAckedReadTaskIds.removeAll(settled)
}
