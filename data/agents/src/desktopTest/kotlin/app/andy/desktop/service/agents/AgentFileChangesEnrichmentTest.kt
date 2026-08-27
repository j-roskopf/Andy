package app.andy.desktop.service.agents

import app.andy.model.AgentChangeSummary
import app.andy.model.AgentEvent
import app.andy.model.AgentFileChange
import app.andy.model.AgentThreadChangeSnapshot
import app.andy.model.AgentToolKind
import app.andy.model.AgentToolState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentFileChangesEnrichmentTest {
    @Test
    fun misplacedFileChangesAreDroppedFromSkillOnlyFollowUpSegment() {
        withRepo { repo, manager, baseline ->
            val snapshot = checkNotNull(
                manager.changeSnapshot(repo.absolutePath, baseline, listOf("playback/src/A.kt")),
            )
            val events = listOf(
                AgentEvent.UserMessage(1, "commit and push"),
                AgentEvent.ToolCall(
                    atMillis = 2,
                    toolName = "edit",
                    summary = "playback/src/A.kt",
                    toolCallId = "call-1",
                    kind = AgentToolKind.Edit,
                    state = AgentToolState.Completed,
                    locations = listOf("playback/src/A.kt"),
                ),
                AgentEvent.AssistantText(3, "Committed and pushed."),
                AgentEvent.UserMessage(4, "/gh-ship-pr"),
                AgentEvent.Thinking(5, "shipping"),
                AgentEvent.FileChanges(
                    atMillis = 6,
                    batchId = "batch-misplaced",
                    baselineTree = baseline,
                    snapshot = snapshot,
                ),
            )

            val enriched = AgentFileChangesEnrichment.enrichIncremental(
                worktrees = manager,
                cwd = repo.absolutePath,
                baseline = baseline,
                events = events,
                segmentPaths = { segment ->
                    segment.filterIsInstance<AgentEvent.ToolCall>()
                        .filter { it.state == AgentToolState.Completed && it.kind == AgentToolKind.Edit }
                        .flatMap { it.locations }
                        .toSet()
                },
            ).display

            val displayed = enriched.filterIsInstance<AgentEvent.FileChanges>()
            assertEquals(1, displayed.size)
            assertEquals("playback/src/A.kt", displayed.single().snapshot.summary.files.single().path)
            val ghShipIndex = enriched.indexOfFirst { it is AgentEvent.UserMessage && it.text == "/gh-ship-pr" }
            val fileChangesIndex = enriched.indexOfFirst { it is AgentEvent.FileChanges }
            assertTrue(fileChangesIndex in 0 until ghShipIndex)
            assertFalse(enriched.drop(ghShipIndex).any { it is AgentEvent.FileChanges })
        }
    }

    @Test
    fun staleFileChangesAreHiddenAfterWorkspaceMatchesBaseline() {
        withRepo { repo, manager, baseline ->
            File(repo, "src/Main.kt").writeText("one\ntwo\n")
            val snapshot = checkNotNull(manager.changeSnapshot(repo.absolutePath, baseline, listOf("src/Main.kt")))
            val change = AgentEvent.FileChanges(
                atMillis = 1,
                batchId = "batch-1",
                baselineTree = baseline,
                snapshot = snapshot,
            )
            assertEquals(change, AgentFileChangesEnrichment.revalidateFileChange(manager, repo.absolutePath, change))

            git(repo, "add", "src/Main.kt")
            git(repo, "commit", "-m", "committed")
            assertNull(AgentFileChangesEnrichment.revalidateFileChange(manager, repo.absolutePath, change))
        }
    }

    @Test
    fun fileChangesCardIsInsertedBeforeAssistantReply() {
        withRepo { repo, manager, baseline ->
            File(repo, "src/A.kt").writeText("new\n")
            val events = listOf(
                AgentEvent.UserMessage(0, "edit"),
                AgentEvent.ToolCall(
                    atMillis = 1,
                    toolName = "edit",
                    summary = "src/A.kt",
                    toolCallId = "call-1",
                    kind = AgentToolKind.Edit,
                    state = AgentToolState.Completed,
                    locations = listOf("src/A.kt"),
                ),
                AgentEvent.AssistantText(2, "Done editing."),
                AgentEvent.TaskResult(atMillis = 3, success = true, finalText = null),
            )

            val enriched = AgentFileChangesEnrichment.enrichIncremental(
                worktrees = manager,
                cwd = repo.absolutePath,
                baseline = baseline,
                events = events,
                segmentPaths = { segment ->
                    segment.filterIsInstance<AgentEvent.ToolCall>()
                        .filter { it.state == AgentToolState.Completed }
                        .flatMap { it.locations }
                        .toSet()
                },
            ).display

            val fileChangesIndex = enriched.indexOfFirst { it is AgentEvent.FileChanges }
            val assistantIndex = enriched.indexOfFirst { it is AgentEvent.AssistantText }
            assertTrue(fileChangesIndex in 0 until assistantIndex)
        }
    }

    @Test
    fun fileChangesAlreadyRecordedMatchesPathsAndBaseline() {
        val snapshot = AgentThreadChangeSnapshot(
            summary = AgentChangeSummary(listOf(AgentFileChange("src/A.kt", 1, 0))),
            diffs = emptyMap(),
        )
        val existing = AgentEvent.FileChanges(
            atMillis = 1,
            batchId = "batch-1",
            baselineTree = "baseline",
            snapshot = snapshot,
        )
        assertTrue(AgentFileChangesEnrichment.fileChangesAlreadyRecorded(listOf(existing), existing))
        assertFalse(AgentFileChangesEnrichment.fileChangesAlreadyRecorded(emptyList(), existing))
    }

    private fun withRepo(block: (File, WorktreeManager, String) -> Unit) {
        val root = File.createTempFile("andy-fc-enrich", null).also { it.delete(); it.mkdirs() }
        val repo = File(root, "repo").apply { mkdirs() }
        try {
            git(repo, "init")
            git(repo, "config", "user.email", "test@example.test")
            git(repo, "config", "user.name", "Test")
            File(repo, "src").mkdirs()
            File(repo, "playback/src").mkdirs()
            File(repo, "src/Main.kt").writeText("one\n")
            File(repo, "playback/src/A.kt").writeText("one\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "initial")
            val manager = WorktreeManager(File(root, "worktrees"))
            val baseline = checkNotNull(manager.captureChangeBaseline(repo.absolutePath))
            File(repo, "playback/src/A.kt").writeText("one\ntwo\n")
            block(repo, manager, baseline)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}
