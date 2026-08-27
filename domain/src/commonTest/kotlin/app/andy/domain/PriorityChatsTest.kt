package app.andy.domain

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PriorityChatsTest {
    private fun task(
        id: String,
        status: AgentStatus? = AgentStatus.Done,
        unread: Boolean = false,
        createdAtMillis: Long = 1,
        startedAtMillis: Long? = 1,
        finishedAtMillis: Long? = null,
    ) = AgentTask(
        id = id,
        title = id,
        prompt = id,
        agent = AgentKind.Codex,
        status = status,
        unread = unread,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun treatsWorkingBlockedUnreadQueuedAndRecentFailuresAsPriority() {
        val now = 1_000_000L
        assertTrue(task("working", AgentStatus.Working).isPriorityChat(now))
        assertTrue(task("blocked", AgentStatus.Blocked).isPriorityChat(now))
        assertTrue(task("unread", unread = true).isPriorityChat(now))
        assertTrue(
            task("recent-error", AgentStatus.Error, finishedAtMillis = now - 60_000)
                .isPriorityChat(now),
        )
        assertTrue(task("queued", status = null, startedAtMillis = null).isPriorityChat(now))
        assertFalse(task("done").isPriorityChat(now))
    }

    @Test
    fun dropsOldReadFailuresFromPriorityButKeepsUnreadOnes() {
        val now = RecentFailedChatTtlMillis + 50_000
        val oldRead = task(
            "old-read",
            AgentStatus.Error,
            finishedAtMillis = 1,
        )
        val oldUnread = task(
            "old-unread",
            AgentStatus.Error,
            unread = true,
            finishedAtMillis = 1,
        )
        assertFalse(oldRead.isPriorityChat(now))
        assertTrue(oldUnread.isPriorityChat(now))
    }

    @Test
    fun splitsPriorityAboveTheRestAndOrdersBlockedThenWorking() {
        val done = task("done", createdAtMillis = 400)
        val unread = task("unread", unread = true, createdAtMillis = 100)
        val working = task("working", AgentStatus.Working, createdAtMillis = 200)
        val blocked = task("blocked", AgentStatus.Blocked, createdAtMillis = 50)
        val split = splitPriorityChats(listOf(done, unread, working, blocked))
        assertEquals(listOf("blocked", "working", "unread"), split.priority.map { it.id })
        assertEquals(listOf("done"), split.rest.map { it.id })
    }

    @Test
    fun keepsPriorityChatsVisibleEvenWhenTheyExceedTheRecentLimit() {
        val priority = List(3) { index ->
            task("p$index", AgentStatus.Working, createdAtMillis = 100L + index)
        }
        val rest = List(4) { index ->
            task("r$index", createdAtMillis = 200L + index)
        }
        val visible = visibleChatSessions(
            sessions = rest + priority,
            pinPriority = true,
            limit = 2,
        )
        // All 3 priority + 2 non-priority (limit), even though priority alone exceeds limit.
        assertEquals(listOf("p2", "p1", "p0", "r0", "r1"), visible.map { it.id })
    }

    @Test
    fun appliesLimitOnlyToNonPriorityRecentsWhenPinned() {
        val working = task("working", AgentStatus.Working, createdAtMillis = 1)
        val older = task("older", createdAtMillis = 10)
        val mid = task("mid", createdAtMillis = 15)
        val newer = task("newer", createdAtMillis = 20)
        val visible = visibleChatSessions(
            sessions = listOf(newer, mid, older, working),
            pinPriority = true,
            limit = 2,
        )
        // All priority chats, plus [limit] non-priority — priority does not consume the budget.
        assertEquals(listOf("working", "newer", "mid"), visible.map { it.id })
    }

    @Test
    fun leavesTheListUnchangedWhenPinningIsOff() {
        val working = task("working", AgentStatus.Working, createdAtMillis = 1)
        val newer = task("newer", createdAtMillis = 20)
        val visible = visibleChatSessions(
            sessions = listOf(newer, working),
            pinPriority = false,
            limit = 1,
        )
        assertEquals(listOf("newer"), visible.map { it.id })
    }
}
