package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AntigravityConversationIdsTest {
    private fun history(home: File): File = File(home, ".gemini/antigravity-cli/history.jsonl")

    @Test
    fun resolveForTaskNeverBackfillsWithoutStoredId() {
        val home = kotlin.io.path.createTempDirectory("andy-agy-resolve").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-agy-resolve-cwd").toFile().absolutePath
        try {
            val conversationId = "aaaaaaaa-0000-0000-0000-000000000000"
            history(home).parentFile.mkdirs()
            history(home).writeText(
                """{"conversationId":"$conversationId","display":"hello","workspace":"$cwd"}""" + "\n",
            )
            // A matching conversation exists in agy's own history, but the task
            // never had an id captured for it. resolveForTask must not
            // fuzzy-guess a match — a missing id is a different bug, not
            // something to paper over.
            val task = AgentTask(
                id = "task-1",
                title = "t",
                prompt = "hello",
                agent = AgentKind.Antigravity,
                cwd = cwd,
                createdAtMillis = 1L,
            )
            assertNull(AntigravityConversationIds.resolveForTask(task, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }

    @Test
    fun resolveForTaskUsesStoredIdWhenTranscriptContainsPrompt() {
        val home = kotlin.io.path.createTempDirectory("andy-agy-resolve-2").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-agy-resolve-cwd-2").toFile().absolutePath
        try {
            val conversationId = "bbbbbbbb-0000-0000-0000-000000000000"
            val transcript = File(
                home,
                ".gemini/antigravity-cli/brain/$conversationId/.system_generated/logs/transcript.jsonl",
            )
            transcript.parentFile.mkdirs()
            transcript.writeText("""{"role":"user","text":"hello"}""" + "\n")
            val task = AgentTask(
                id = "task-2",
                title = "t",
                prompt = "hello",
                agent = AgentKind.Antigravity,
                cwd = cwd,
                vendorSessionId = conversationId,
                createdAtMillis = 1L,
            )
            assertEquals(conversationId, AntigravityConversationIds.resolveForTask(task, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }

    /**
     * Reproduces the reported bug: a separate, older conversation ("what are the
     * shortcomings...") happens to also contain the literal text "hello" further
     * along in its history, and its entry sorts after this task's own conversation
     * in the file. resolveForTask must stay bound to the task's own stored id
     * rather than drift onto the unrelated conversation.
     */
    @Test
    fun resolveForTaskIgnoresUnrelatedConversationThatAlsoMatchesPromptText() {
        val home = kotlin.io.path.createTempDirectory("andy-agy-resolve-3").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-agy-resolve-cwd-3").toFile().absolutePath
        try {
            val ownConversationId = "cccccccc-0000-0000-0000-000000000000"
            val unrelatedConversationId = "dddddddd-0000-0000-0000-000000000000"
            history(home).parentFile.mkdirs()
            history(home).writeText(
                buildString {
                    appendLine(
                        """{"conversationId":"$unrelatedConversationId","display":"analyze this project, any shortcomings","workspace":"$cwd"}""",
                    )
                    appendLine("""{"conversationId":"$unrelatedConversationId","display":"hello","workspace":"$cwd"}""")
                },
            )
            val ownTranscript = File(
                home,
                ".gemini/antigravity-cli/brain/$ownConversationId/.system_generated/logs/transcript.jsonl",
            )
            ownTranscript.parentFile.mkdirs()
            ownTranscript.writeText("""{"role":"user","text":"hello"}""" + "\n")
            val task = AgentTask(
                id = "task-3",
                title = "t",
                prompt = "hello",
                agent = AgentKind.Antigravity,
                cwd = cwd,
                vendorSessionId = ownConversationId,
                createdAtMillis = 1L,
            )
            assertEquals(ownConversationId, AntigravityConversationIds.resolveForTask(task, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }
}
