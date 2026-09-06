package com.joetr.andy.mobile.data.attention

import com.joetr.andy.mobile.data.networkaccess.ChatDto
import com.joetr.andy.mobile.data.networkaccess.UserInputRequestDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttentionTrackerTest {
    @Test
    fun seedsWithoutNotifyingThenFiresDoneAndError() {
        val tracker = ChatAttentionTracker(nowMillis = { 1_000L })
        assertTrue(tracker.onChatsChanged(listOf(chat(status = "Working"))).isEmpty())
        assertEquals(
            listOf(ChatAttentionKind.Done),
            tracker.onChatsChanged(listOf(chat(status = "Done"))).map { it.kind },
        )
        assertEquals(
            listOf(ChatAttentionKind.Error),
            tracker.onChatsChanged(listOf(chat(status = "Error"))).map { it.kind },
        )
    }

    @Test
    fun blockedAndFreshInputRequestNotify() {
        val tracker = ChatAttentionTracker(nowMillis = { 1_000L })
        tracker.onChatsChanged(listOf(chat(status = "Working")))
        assertEquals(
            listOf(ChatAttentionKind.Blocked),
            tracker.onChatsChanged(listOf(chat(status = "Blocked", inputId = "r1"))).map { it.kind },
        )
        // Same blocked + same request: no repeat.
        assertTrue(
            tracker.onChatsChanged(listOf(chat(status = "Blocked", inputId = "r1"))).isEmpty(),
        )
        // New request while still blocked: notify again.
        assertEquals(
            listOf(ChatAttentionKind.Blocked),
            tracker.onChatsChanged(listOf(chat(status = "Blocked", inputId = "r2"))).map { it.kind },
        )
    }

    @Test
    fun dedupesSameKindWithinWindow() {
        var now = 1_000L
        val tracker = ChatAttentionTracker(nowMillis = { now }, sameKindWindowMs = 5_000L)
        tracker.onChatsChanged(listOf(chat(status = "Working")))
        assertEquals(1, tracker.onChatsChanged(listOf(chat(status = "Done"))).size)
        tracker.onChatsChanged(listOf(chat(status = "Working")))
        now = 2_000L
        assertTrue(tracker.onChatsChanged(listOf(chat(status = "Done"))).isEmpty())
        now = 7_000L
        tracker.onChatsChanged(listOf(chat(status = "Working")))
        assertEquals(1, tracker.onChatsChanged(listOf(chat(status = "Done"))).size)
    }

    @Test
    fun notifiesWhenChatAppearsAlreadyDoneAfterSeed() {
        val tracker = ChatAttentionTracker(nowMillis = { 1_000L })
        assertTrue(tracker.onChatsChanged(emptyList()).isEmpty())
        assertEquals(
            listOf(ChatAttentionKind.Done),
            tracker.onChatsChanged(listOf(chat(status = "Done"))).map { it.kind },
        )
    }

    @Test
    fun notifiesWhenFinishedAtLatchesEvenIfStatusBlank() {
        val tracker = ChatAttentionTracker(nowMillis = { 1_000L })
        tracker.onChatsChanged(listOf(chat(status = "Working", finishedAtMillis = 0)))
        assertEquals(
            listOf(ChatAttentionKind.Done),
            tracker.onChatsChanged(
                listOf(chat(status = "", finishedAtMillis = 99)),
            ).map { it.kind },
        )
    }

    @Test
    fun finishedAtThenStatusDoneDoesNotDoubleNotify() {
        val tracker = ChatAttentionTracker(nowMillis = { 1_000L })
        tracker.onChatsChanged(listOf(chat(status = "Working", finishedAtMillis = 0)))
        assertEquals(
            listOf(ChatAttentionKind.Done),
            tracker.onChatsChanged(
                listOf(chat(status = "Working", finishedAtMillis = 99)),
            ).map { it.kind },
        )
        // Host often latches finishedAt before the status string becomes "Done".
        assertTrue(
            tracker.onChatsChanged(
                listOf(chat(status = "Done", finishedAtMillis = 99)),
            ).isEmpty(),
        )
    }

    @Test
    fun notificationTitlePrefersTruncatedPrompt() {
        val long = "x".repeat(80)
        assertEquals(
            "x".repeat(59) + "…",
            ChatAttentionTracker.notificationTitle(chat(prompt = long, title = "ignored")),
        )
        assertEquals(
            "Hello",
            ChatAttentionTracker.notificationTitle(chat(prompt = "", title = "Hello")),
        )
    }

    private fun chat(
        id: String = "task",
        status: String = "",
        title: String = "title",
        prompt: String = "prompt text",
        inputId: String? = null,
        finishedAtMillis: Long = 0L,
    ) = ChatDto(
        id = id,
        title = title,
        prompt = prompt,
        status = status,
        finishedAtMillis = finishedAtMillis,
        userInputRequest = inputId?.let { UserInputRequestDto(id = it) },
    )
}
