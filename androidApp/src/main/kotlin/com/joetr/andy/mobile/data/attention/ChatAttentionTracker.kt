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
)

class ChatAttentionTracker(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val sameKindWindowMs: Long = SAME_KIND_WINDOW_MS,
) {
    private data class Tracked(
        val status: String,
        val inputRequestId: String?,
        val finishedAtMillis: Long,
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
            val isNew = prior == null
            val statusChanged = prior != null && prior.status != next.status
            val newlyFinished =
                prior != null &&
                    prior.finishedAtMillis == 0L &&
                    next.finishedAtMillis > 0L &&
                    kind == ChatAttentionKind.Done
            val newInputRequest =
                kind == ChatAttentionKind.Blocked &&
                    next.inputRequestId != null &&
                    next.inputRequestId != prior?.inputRequestId
            // New chat already in an attention state (finished between polls), or a
            // real transition / new input request / finishedAt latch.
            if (!isNew && !statusChanged && !newInputRequest && !newlyFinished) continue
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
            )
        }
        previous.keys.retainAll(chats.map { it.id }.toSet())
        return events
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

        fun notificationTitle(chat: ChatDto): String {
            val prompt = chat.prompt.takeIf { it.isNotBlank() }
            val text = prompt ?: chat.title.takeIf { it.isNotBlank() } ?: "Chat"
            val flat = text.replace('\n', ' ').trim()
            return if (flat.length <= 60) flat else flat.take(59) + "…"
        }

        fun subtitle(kind: ChatAttentionKind): String = when (kind) {
            ChatAttentionKind.Blocked -> "Needs your input"
            ChatAttentionKind.Done -> "Agent completed"
            ChatAttentionKind.Error -> "Agent failed"
        }

        private fun track(chat: ChatDto) = Tracked(
            status = chat.status.trim(),
            inputRequestId = chat.userInputRequest?.id,
            finishedAtMillis = chat.finishedAtMillis,
        )
    }
}
