package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAgentRetentionEligibilityTest {
    private val now = 100_000L
    private val cutoffArchive = 90_000L
    private val cutoffDelete = 80_000L

    private fun task(
        createdAtMillis: Long = 1L,
        finishedAtMillis: Long? = null,
        status: AgentStatus = AgentStatus.Done,
        unread: Boolean = false,
        archived: Boolean = false,
        transcriptCompressed: Boolean = false,
    ) = AgentTask(
        id = "task",
        title = "task",
        prompt = "prompt",
        agent = AgentKind.Codex,
        createdAtMillis = createdAtMillis,
        finishedAtMillis = finishedAtMillis,
        status = status,
        unread = unread,
        archived = archived,
        transcriptCompressed = transcriptCompressed,
    )

    @Test
    fun guardsAlwaysSkip() {
        assertEquals(RetentionAction.Skip, retentionAction(task(status = AgentStatus.Working, createdAtMillis = 1), now, cutoffArchive, cutoffDelete))
        assertEquals(RetentionAction.Skip, retentionAction(task(unread = true, createdAtMillis = 1), now, cutoffArchive, cutoffDelete))
        assertEquals(RetentionAction.Skip, retentionAction(task(archived = true, createdAtMillis = 1), now, cutoffArchive, cutoffDelete))
    }

    @Test
    fun oldUnarchivedTaskCompressesAndCompressedArchiveDeletes() {
        assertEquals(
            RetentionAction.CompressArchive,
            retentionAction(task(createdAtMillis = 89_999), now, cutoffArchive, cutoffDelete),
        )
        assertEquals(
            RetentionAction.PermanentDelete,
            retentionAction(task(createdAtMillis = 79_999, archived = true, transcriptCompressed = true), now, cutoffArchive, cutoffDelete),
        )
    }

    @Test
    fun unarchivedCompressedTaskSkipsRecompression() {
        assertEquals(
            RetentionAction.Skip,
            retentionAction(
                task(createdAtMillis = 89_999, archived = false, transcriptCompressed = true),
                now,
                cutoffArchive,
                cutoffDelete,
            ),
        )
    }

    @Test
    fun freshTaskSkipsAndFinishedAtIsTheAgeBasis() {
        assertEquals(RetentionAction.Skip, retentionAction(task(createdAtMillis = 1, finishedAtMillis = 95_000), now, cutoffArchive, cutoffDelete))
        assertEquals(RetentionAction.CompressArchive, retentionAction(task(createdAtMillis = 95_000, finishedAtMillis = 89_999), now, cutoffArchive, cutoffDelete))
    }
}
