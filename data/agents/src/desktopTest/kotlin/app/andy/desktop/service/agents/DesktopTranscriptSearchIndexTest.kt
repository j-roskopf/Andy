package app.andy.desktop.service.agents

import app.andy.model.AgentEvent
import app.andy.store.openAndyAgentDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTranscriptSearchIndexTest {
    @Test
    fun searchableTextSkipsThinkingAndToolDetail() {
        assertEquals(
            "hello",
            DesktopTranscriptSearchIndex.searchableText(AgentEvent.UserMessage(1L, "hello")),
        )
        assertEquals(
            "reply",
            DesktopTranscriptSearchIndex.searchableText(AgentEvent.AssistantText(1L, "reply")),
        )
        assertNull(
            DesktopTranscriptSearchIndex.searchableText(
                AgentEvent.AssistantText(1L, "delta", isStreamDelta = true),
            ),
        )
        assertNull(
            DesktopTranscriptSearchIndex.searchableText(AgentEvent.Thinking(1L, "secret plan")),
        )
        assertEquals(
            "Read path/to/file",
            DesktopTranscriptSearchIndex.searchableText(
                AgentEvent.ToolCall(1L, "Read", "path/to/file", detail = "huge dump"),
            ),
        )
    }

    @Test
    fun ftsMatchQueryBuildsPrefixAnd() {
        assertEquals("auth* AND token*", DesktopTranscriptSearchIndex.ftsMatchQuery("auth token"))
        assertNull(DesktopTranscriptSearchIndex.ftsMatchQuery("a"))
        assertNull(DesktopTranscriptSearchIndex.ftsMatchQuery("  "))
    }

    @Test
    fun snippetSurroundsMatch() {
        val body = "alpha beta gamma delta epsilon"
        val snippet = DesktopTranscriptSearchIndex.snippetFor(body, "gamma", radius = 6)
        assertNotNull(snippet)
        assertTrue(snippet.contains("gamma"))
    }

    @Test
    fun searchStreamsHitFromIndexedTranscript() = runBlocking {
        val root = File.createTempFile("andy-transcript-search", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val dbFile = File(root, "agents.db")
            val handle = openAndyAgentDatabase(dbFile)
            val taskId = "chat-1"
            val transcript = File(root, "$taskId/transcript.jsonl")
            transcript.parentFile.mkdirs()
            val store = app.andy.desktop.service.agents.acp.AcpTranscriptStore(fileFor = { File(root, "$it/transcript.jsonl") })
            store.append(taskId, AgentEvent.UserMessage(1L, "please wire oauth refresh"))
            store.append(taskId, AgentEvent.AssistantText(2L, "Sure, updating the token path"))

            val index = DesktopTranscriptSearchIndex(handle.driver) { id ->
                File(root, "$id/transcript.jsonl")
            }
            index.reindexTask(taskId)

            val hits = index.search(
                query = "oauth",
                tasks = listOf(
                    DesktopTranscriptSearchIndex.TaskRef(
                        taskId = taskId,
                        projectId = "proj",
                        title = "OAuth work",
                        projectName = "Andy",
                        updatedAtMillis = 10L,
                    ),
                ),
            ).toList()

            assertEquals(1, hits.size)
            assertEquals(taskId, hits.single().taskId)
            assertTrue(hits.single().snippet.contains("oauth", ignoreCase = true))
            assertEquals("Andy", hits.single().projectName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun coldTaskIsIndexedDuringSearch() = runBlocking {
        val root = File.createTempFile("andy-transcript-cold", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val dbFile = File(root, "agents.db")
            val handle = openAndyAgentDatabase(dbFile)
            val taskId = "cold-1"
            val store = app.andy.desktop.service.agents.acp.AcpTranscriptStore(fileFor = { File(root, "$it/transcript.jsonl") })
            File(root, taskId).mkdirs()
            store.append(taskId, AgentEvent.UserMessage(1L, "unique-zebra-phrase in body"))

            val index = DesktopTranscriptSearchIndex(handle.driver) { id ->
                File(root, "$id/transcript.jsonl")
            }
            // Do not reindex upfront — search should cold-index.
            val hits = index.search(
                query = "zebra",
                tasks = listOf(
                    DesktopTranscriptSearchIndex.TaskRef(
                        taskId = taskId,
                        projectId = null,
                        title = "Cold",
                        projectName = null,
                        updatedAtMillis = 1L,
                    ),
                ),
            ).toList()
            assertEquals(1, hits.size)
            assertTrue(hits.single().snippet.contains("zebra", ignoreCase = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun excludeTaskIdsSkipsHits() = runBlocking {
        val root = File.createTempFile("andy-transcript-exclude", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val handle = openAndyAgentDatabase(File(root, "agents.db"))
            val taskId = "ex-1"
            val store = app.andy.desktop.service.agents.acp.AcpTranscriptStore(fileFor = { File(root, "$it/transcript.jsonl") })
            File(root, taskId).mkdirs()
            store.append(taskId, AgentEvent.UserMessage(1L, "needle in transcript"))
            val index = DesktopTranscriptSearchIndex(handle.driver) { id ->
                File(root, "$id/transcript.jsonl")
            }
            index.reindexTask(taskId)
            val hits = index.search(
                query = "needle",
                tasks = listOf(
                    DesktopTranscriptSearchIndex.TaskRef(taskId, null, "T", null, 1L),
                ),
                excludeTaskIds = setOf(taskId),
            ).toList()
            assertTrue(hits.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removeTaskClearsIndex() = runBlocking {
        val root = File.createTempFile("andy-transcript-remove", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val handle = openAndyAgentDatabase(File(root, "agents.db"))
            val taskId = "rm-1"
            val store = app.andy.desktop.service.agents.acp.AcpTranscriptStore(fileFor = { File(root, "$it/transcript.jsonl") })
            File(root, taskId).mkdirs()
            store.append(taskId, AgentEvent.UserMessage(1L, "vanishing text"))
            val index = DesktopTranscriptSearchIndex(handle.driver) { id ->
                File(root, "$id/transcript.jsonl")
            }
            index.reindexTask(taskId)
            index.removeTask(taskId)
            // Delete transcript so cold path cannot revive the hit.
            File(root, "$taskId/transcript.jsonl").delete()
            val hits = index.search(
                query = "vanishing",
                tasks = listOf(
                    DesktopTranscriptSearchIndex.TaskRef(taskId, null, "T", null, 1L),
                ),
            ).toList()
            assertTrue(hits.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun buildSearchableBodyJoinsParts() {
        val body = DesktopTranscriptSearchIndex.buildSearchableBody(
            listOf(
                AgentEvent.UserMessage(1L, "one"),
                AgentEvent.Thinking(2L, "skip"),
                AgentEvent.AssistantText(3L, "two"),
            ),
        )
        assertEquals("one\ntwo", body)
        assertFalse(body.contains("skip"))
    }
}
