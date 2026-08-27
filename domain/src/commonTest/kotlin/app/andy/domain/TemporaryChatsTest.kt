package app.andy.domain

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TemporaryChatsTest {
    private fun task(
        id: String,
        temporary: Boolean = false,
        createdAtMillis: Long = 0,
        status: AgentStatus? = null,
        startedAtMillis: Long? = null,
        ownsWorktree: Boolean = false,
    ) = AgentTask(
        id = id,
        title = id,
        prompt = id,
        agent = AgentKind.Codex,
        temporary = temporary,
        status = status,
        startedAtMillis = startedAtMillis,
        ownsWorktree = ownsWorktree,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun excludingTemporaryDropsOnlyTemporaryChats() {
        val tasks = listOf(task("a"), task("b", temporary = true), task("c"))
        assertEquals(listOf("a", "c"), tasks.excludingTemporary().map { it.id })
    }

    /**
     * The common case must not allocate a new list: callers pass the result straight into
     * `remember(...)` keys, and a fresh instance every recomposition would defeat them.
     */
    @Test
    fun excludingTemporaryKeepsIdentityWhenNothingIsTemporary() {
        val tasks = listOf(task("a"), task("b"))
        assertSame(tasks, tasks.excludingTemporary())
    }

    @Test
    fun onlyTemporarySelectsTemporaryChatsNewestFirst() {
        val tasks = listOf(
            task("old", temporary = true, createdAtMillis = 1),
            task("permanent"),
            task("new", temporary = true, createdAtMillis = 5),
        )
        assertEquals(listOf("new", "old"), tasks.onlyTemporary().temporaryChatOrder().map { it.id })
    }

    @Test
    fun untouchedTemporaryChatDiscardsWithoutConfirmation() {
        assertFalse(temporaryChatNeedsDiscardConfirm(task("queued", temporary = true)))
    }

    @Test
    fun startedTemporaryChatConfirmsBeforeDiscard() {
        val started = task(
            "started",
            temporary = true,
            status = AgentStatus.Done,
            startedAtMillis = 10,
        )
        assertTrue(temporaryChatNeedsDiscardConfirm(started))
    }

    @Test
    fun temporaryChatOwningAWorktreeConfirmsEvenBeforeLaunch() {
        assertTrue(temporaryChatNeedsDiscardConfirm(task("wt", temporary = true, ownsWorktree = true)))
    }
}
