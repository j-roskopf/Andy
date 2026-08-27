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
    fun settledClientReadStopsMaskingNewerDaemonUnread() {
        // The user opened "a" (ack recorded), the daemon applied the read, and only then did
        // the turn finish and re-badge it. The list below was fetched after that ack landed,
        // so its unread=true is newer than the ack and must survive.
        val clientReads = linkedSetOf("a")
        val daemonAcked = linkedSetOf("a")
        val refreshed = listOf(task("a", unread = true))

        dropSettledClientReads(clientReads, daemonAcked, settled = setOf("a"))
        dropConfirmedClientReads(clientReads, refreshed)
        val merged = mergeRefreshedAgentTasks(refreshed, clientReads, viewingTaskIds = emptySet())

        assertTrue(merged.single().unread)
        assertTrue(clientReads.isEmpty())
        assertTrue(daemonAcked.isEmpty())
    }

    @Test
    fun unsettledClientReadStillMasksDaemonUnread() {
        // Same shape, but the mark_read RPC has not been acknowledged yet, so the daemon's
        // unread is stale rather than newer and the local ack must still win.
        val clientReads = linkedSetOf("a")
        val daemonAcked = linkedSetOf<String>()
        val refreshed = listOf(task("a", unread = true))

        dropSettledClientReads(clientReads, daemonAcked, settled = daemonAcked.toSet())
        dropConfirmedClientReads(clientReads, refreshed)
        val merged = mergeRefreshedAgentTasks(refreshed, clientReads, viewingTaskIds = emptySet())

        assertFalse(merged.single().unread)
        assertEquals(setOf("a"), clientReads)
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
