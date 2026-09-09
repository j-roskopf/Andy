package com.joetr.andy.mobile.data.attention

import com.joetr.andy.mobile.data.networkaccess.ChatDto

/**
 * Remote attention tracker for Network Access chat lists.
 *
 * Unlike the desktop coordinator, after the initial seed we **do** notify when a
 * chat first appears already Blocked/Done/Error — short chats can finish between
 * 2.5s polls without ever being observed as Working.
 */
enum class ChatAttentionKind {
    Blocked,
    Done,
    Error,
}

data class ChatAttentionEvent(
    val chatId: String,
    val projectId: String?,
    val title: String,
    val kind: ChatAttentionKind,
    val planMode: Boolean = false,
)

class ChatAttentionTracker(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sameKindWindowMs: Long = SAME_KIND_WINDOW_MS,
) {
    private data class Tracked(
        val status: String,
        val inputRequestId: String?,
        val finishedAtMillis: Long,
        val planMode: Boolean,
    )

    private val previous = mutableMapOf<String, Tracked>()
    private val recentByKey = mutableMapOf<String, Long>()
    private var seeded = false

    fun reset() {
        previous.clear()
        recentByKey.clear()
        seeded = false
    }

    /**
     * @return attention events that should surface as OS notifications.
     *
     * Only fires when a chat **enters** an attention kind (or gets a new Blocked
     * input request). Status-string churn after `finishedAt` latches (e.g. blank →
     * "Done") must not re-alert — that was re-firing the same completion after
     * reconnects / config changes.
     */
    fun onChatsChanged(chats: List<ChatDto>): List<ChatAttentionEvent> {
        if (!seeded) {
            chats.forEach { previous[it.id] = track(it) }
            seeded = true
            return emptyList()
        }
        val events = mutableListOf<ChatAttentionEvent>()
        for (chat in chats) {
            val next = track(chat)
            val prior = previous.put(chat.id, next)
            val kind = attentionKind(chat) ?: continue
            val priorKind = prior?.let { attentionKindFromTracked(it) }
            val newInputRequest =
                kind == ChatAttentionKind.Blocked &&
                    next.inputRequestId != null &&
                    next.inputRequestId != prior?.inputRequestId
            // New / first-seen attention state, or a fresh input request while blocked.
            if (priorKind == kind && !newInputRequest) continue
            val dedupeKey = when {
                kind == ChatAttentionKind.Blocked && next.inputRequestId != null ->
                    "${chat.id}:Blocked:${next.inputRequestId}"
                else -> "${chat.id}:${kind.name}"
            }
            if (!tryMarkNotified(dedupeKey)) continue
            events += ChatAttentionEvent(
                chatId = chat.id,
                projectId = chat.projectId.takeIf { it.isNotBlank() },
                title = notificationTitle(chat),
                kind = kind,
                planMode = kind == ChatAttentionKind.Done && chat.planMode,
            )
        }
        previous.keys.retainAll(chats.map { it.id }.toSet())
        return events
    }

    private fun attentionKindFromTracked(tracked: Tracked): ChatAttentionKind? {
        val status = tracked.status
        return when {
            status.equals("Done", ignoreCase = true) -> ChatAttentionKind.Done
            status.equals("Error", ignoreCase = true) -> ChatAttentionKind.Error
            status.equals("Blocked", ignoreCase = true) || tracked.inputRequestId != null ->
                ChatAttentionKind.Blocked
            tracked.finishedAtMillis > 0L -> ChatAttentionKind.Done
            else -> null
        }
    }

    private fun tryMarkNotified(key: String): Boolean {
        val now = nowMillis()
        val last = recentByKey[key]
        if (last != null && now - last < sameKindWindowMs) return false
        recentByKey[key] = now
        return true
    }

    companion object {
        private const val SAME_KIND_WINDOW_MS = 5_000L

        fun attentionKind(chat: ChatDto): ChatAttentionKind? {
            val status = chat.status.trim()
            return when {
                status.equals("Done", ignoreCase = true) -> ChatAttentionKind.Done
                status.equals("Error", ignoreCase = true) -> ChatAttentionKind.Error
                status.equals("Blocked", ignoreCase = true) || chat.userInputRequest != null ->
                    ChatAttentionKind.Blocked
                // Status string can lag; finishedAt is set when the run ends.
                chat.finishedAtMillis > 0L -> ChatAttentionKind.Done
                else -> null
            }
        }

        /**
         * True while an agent is mid-turn, so another attention event is still coming.
         *
         * Drives the listener's idle shutdown: Done/Blocked/Error are terminal until the *user*
         * acts, and when they do it is from this app, which restarts the service itself.
         */
        fun isWorking(chat: ChatDto): Boolean {
            if (chat.archived) return false
            val status = chat.status.trim()
            if (status.equals("Working", ignoreCase = true)) return true
            // Status can lag the run (see attentionKind) — a started, unfinished chat with no
            // question outstanding is still live.
            return status.isBlank() &&
                chat.startedAtMillis > 0L &&
                chat.finishedAtMillis == 0L &&
                chat.userInputRequest == null
        }

        fun notificationTitle(chat: ChatDto): String {
            val prompt = chat.prompt.takeIf { it.isNotBlank() }
            val text = prompt ?: chat.title.takeIf { it.isNotBlank() } ?: "Chat"
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= 60) flat else flat.take(59) + "…"
        }

        fun subtitle(kind: ChatAttentionKind, planMode: Boolean = false): String = when (kind) {
            ChatAttentionKind.Blocked -> "Needs your input"
            ChatAttentionKind.Done -> if (planMode) "Plan ready" else "Agent completed"
            ChatAttentionKind.Error -> "Agent failed"
        }

        private fun track(chat: ChatDto) = Tracked(
            status = chat.status.trim(),
            inputRequestId = chat.userInputRequest?.id,
            finishedAtMillis = chat.finishedAtMillis,
            planMode = chat.planMode,
        )
    }
}
