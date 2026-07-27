package app.andy.desktop.service

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpAgentTaskListSyncTest {
    private fun task(id: String, unread: Boolean) = AgentTask(
        id = id,
        title = id,
        prompt = "",
        agent = AgentKind.Codex,
        status = AgentStatus.Done,
        createdAtMillis = 1,
        unread = unread,
    )

    @Test
    fun mergeKeepsClientReadWhileDaemonStillUnread() {
        val refreshed = listOf(
            task("a", unread = true),
            task("b", unread = true),
        )
        val merged = mergeRefreshedAgentTasks(
            refreshed = refreshed,
            clientReadTaskIds = setOf("a", "b"),
            viewingTaskIds = emptySet(),
        )
        assertFalse(merged.single { it.id == "a" }.unread)
        assertFalse(merged.single { it.id == "b" }.unread)
    }

    @Test
    fun mergeKeepsCurrentlyViewedChatReadDuringSync() {
        val merged = mergeRefreshedAgentTasks(
            refreshed = listOf(task("open", unread = true)),
            clientReadTaskIds = emptySet(),
            viewingTaskIds = setOf("open"),
        )
        assertFalse(merged.single().unread)
    }

    @Test
    fun mergeDoesNotTouchUnrelatedUnreadChats() {
        val merged = mergeRefreshedAgentTasks(
            refreshed = listOf(task("other", unread = true)),
            clientReadTaskIds = setOf("read"),
            viewingTaskIds = emptySet(),
        )
        assertTrue(merged.single { it.id == "other" }.unread)
    }

    @Test
    fun dropConfirmedClientReadsRemovesAckedIds() {
        val ids = linkedSetOf("a", "b")
        dropConfirmedClientReads(
            ids,
            refreshed = listOf(
                task("a", unread = false),
                task("b", unread = true),
            ),
        )
        assertEquals(setOf("b"), ids)
    }

    @Test
    fun withoutClientReadAckDaemonUnreadReturnsAfterRestart() {
        // Documents why setChatViewing must call chat.mark_read: session-local
        // clientReadTaskIds alone cannot survive GUI restart.
        val afterRestart = mergeRefreshedAgentTasks(
            refreshed = listOf(task("sticky", unread = true)),
            clientReadTaskIds = emptySet(),
            viewingTaskIds = emptySet(),
        )
        assertTrue(afterRestart.single().unread)
    }
}
