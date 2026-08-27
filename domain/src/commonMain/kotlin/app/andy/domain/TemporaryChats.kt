package app.andy.domain

import app.andy.model.AgentTask

/**
 * Temporary chats are desktop-session-local: they are never persisted, and they are filtered
 * out of every surface that outlives or reaches beyond this process — the agent store, the MCP
 * tool set, the web chat server and its push notifications, automations, and kanban boards.
 *
 * They deliberately stay in the live task list so status tracking, terminal attach, follow-ups
 * and user-input cards need no special cases; only the boundaries below know about them.
 */
fun List<AgentTask>.excludingTemporary(): List<AgentTask> =
    if (none { it.temporary }) this else filterNot { it.temporary }

fun List<AgentTask>.onlyTemporary(): List<AgentTask> = filter { it.temporary }

/**
 * Rail ordering for the Temporary section: newest first, so a chat opened mid-session lands on
 * top. Priority ordering is deliberately not applied — the section is short-lived and small.
 */
fun List<AgentTask>.temporaryChatOrder(): List<AgentTask> = sortedByDescending { it.createdAtMillis }

/**
 * True when discarding [task] would throw away something the user might not expect to lose.
 * An untouched temp chat (queued, never sent, no session) discards without a prompt.
 */
fun temporaryChatNeedsDiscardConfirm(task: AgentTask): Boolean =
    task.isActive || !task.isQueued || task.ownsWorktree
