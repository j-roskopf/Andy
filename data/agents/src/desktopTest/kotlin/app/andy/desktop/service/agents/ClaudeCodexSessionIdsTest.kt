package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class ClaudeCodexSessionIdsTest {
    @Test
    fun vendorSessionMatchingComparesPromptPrefixes() {
        assertTrue(
            VendorSessionMatching.promptMatches(
                "fix the navbar bug in live view",
                "fix the navbar bug",
            ),
        )
        assertFalse(VendorSessionMatching.promptMatches("", "hello"))
    }

    @Test
    fun claudeEncodesProjectPath() {
        val path = File("andy-claude-encode-test").absoluteFile.normalize().path
        val expected = path.replace(Regex("""[/\\:]"""), "-")
        assertEquals(expected, ClaudeSessionIds.encodeProjectPath(path))
    }

    @Test
    fun claudeResolveForTaskNeverBackfillsWithoutStoredId() {
        val home = kotlin.io.path.createTempDirectory("andy-claude-resolve").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-claude-resolve-cwd").toFile().absolutePath
        try {
            val encoded = ClaudeSessionIds.encodeProjectPath(cwd)!!
            val project = File(home, ".claude/projects/$encoded")
            project.mkdirs()
            val sessionId = "22222222-2222-2222-2222-222222222222"
            File(project, "$sessionId.jsonl").writeText(
                """{"type":"queue-operation","sessionId":"$sessionId","content":"ship the navbar fix"}""",
            )
            // A matching session exists on disk, but the task never had an id
            // captured for it. resolveForTask must not fuzzy-guess a match —
            // a missing id is a different bug, not something to paper over.
            val task = AgentTask(
                id = "task-1",
                title = "t",
                prompt = "ship the navbar fix",
                agent = AgentKind.ClaudeCode,
                cwd = cwd,
                createdAtMillis = 1L,
            )
            assertNull(ClaudeSessionIds.resolveForTask(task, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }

    @Test
    fun claudeResolveForTaskUsesStoredIdWhenPromptMatches() {
        val home = kotlin.io.path.createTempDirectory("andy-claude-resolve-2").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-claude-resolve-cwd-2").toFile().absolutePath
        try {
            val encoded = ClaudeSessionIds.encodeProjectPath(cwd)!!
            val project = File(home, ".claude/projects/$encoded")
            project.mkdirs()
            val sessionId = "22222222-2222-2222-2222-222222222222"
            File(project, "$sessionId.jsonl").writeText(
                """{"type":"queue-operation","sessionId":"$sessionId","content":"ship the navbar fix"}""",
            )
            val task = AgentTask(
                id = "task-1",
                title = "t",
                prompt = "ship the navbar fix",
                agent = AgentKind.ClaudeCode,
                cwd = cwd,
                vendorSessionId = sessionId,
                createdAtMillis = 1L,
            )
            assertEquals(sessionId, ClaudeSessionIds.resolveForTask(task, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }

    @Test
    fun codexParsesRolloutSessionIdFromFilename() {
        assertEquals(
            "019fb87a-ab68-7a92-8c1f-b3e2562d4514",
            CodexSessionIds.parseSessionIdFromRolloutName(
                "rollout-2026-07-31T09-01-14-019fb87a-ab68-7a92-8c1f-b3e2562d4514.jsonl",
            ),
        )
    }

    @Test
    fun codexResolveForTaskNeverBackfillsWithoutStoredId() {
        val home = kotlin.io.path.createTempDirectory("andy-codex-home").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-codex-cwd").toFile().absolutePath
        try {
            val sessionId = "33333333-3333-3333-3333-333333333333"
            val rollout = File(home, ".codex/sessions/2026/07/31/rollout-test-$sessionId.jsonl")
            rollout.parentFile.mkdirs()
            rollout.writeText(
                """
                {"type":"session_meta","payload":{"session_id":"$sessionId","cwd":"$cwd"}}
                {"type":"event_msg","payload":{"type":"user_message","message":"add dark mode toggle"}}
                """.trimIndent(),
            )
            // A matching rollout exists on disk, but the task never had an id
            // captured for it. resolveForTask must not fuzzy-guess a match —
            // a missing id is a different bug, not something to paper over.
            val task = AgentTask(
                id = "task-3",
                title = "t",
                prompt = "add dark mode toggle",
                agent = AgentKind.Codex,
                cwd = cwd,
                createdAtMillis = 1L,
            )
            assertNull(CodexSessionIds.resolveForTask(task, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }

    @Test
    fun codexResolveForTaskUsesStoredIdWhenPromptMatches() {
        val home = kotlin.io.path.createTempDirectory("andy-codex-resolve").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-codex-resolve-cwd").toFile().absolutePath
        try {
            val sessionId = "44444444-4444-4444-4444-444444444444"
            val rollout = File(home, ".codex/sessions/2026/07/31/rollout-x-$sessionId.jsonl")
            rollout.parentFile.mkdirs()
            rollout.writeText(
                """{"type":"event_msg","payload":{"type":"user_message","message":"wire up notifications"}}""",
            )
            val task = AgentTask(
                id = "task-2",
                title = "t",
                prompt = "wire up notifications",
                agent = AgentKind.Codex,
                cwd = cwd,
                vendorSessionId = sessionId,
                createdAtMillis = 1L,
            )
            assertEquals(sessionId, CodexSessionIds.resolveForTask(task, home))
            // A stored id that does not actually correspond to any rollout for
            // this task must not be papered over by re-discovering some other
            // session that happens to match the prompt text.
            assertNull(
                CodexSessionIds.resolveForTask(
                    task.copy(vendorSessionId = "55555555-5555-5555-5555-555555555555"),
                    home,
                ),
            )
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }
}
