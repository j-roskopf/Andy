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
    fun claudeFindsSessionByPromptInProjectJsonl() {
        val home = kotlin.io.path.createTempDirectory("andy-claude-home").toFile()
        val cwd = kotlin.io.path.createTempDirectory("andy-claude-cwd").toFile().absolutePath
        try {
            val encoded = ClaudeSessionIds.encodeProjectPath(cwd)!!
            val project = File(home, ".claude/projects/$encoded")
            project.mkdirs()
            val sessionId = "11111111-1111-1111-1111-111111111111"
            File(project, "$sessionId.jsonl").writeText(
                """
                {"type":"queue-operation","operation":"enqueue","sessionId":"$sessionId","content":"build the feature flag UI"}
                """.trimIndent(),
            )
            val found = ClaudeSessionIds.findByPrompt("build the feature flag UI", cwd, home)
            assertEquals(sessionId, found)
            assertTrue(ClaudeSessionIds.sessionContainsPrompt(sessionId, "build the feature flag", cwd, home))
        } finally {
            home.deleteRecursively()
            File(cwd).deleteRecursively()
        }
    }

    @Test
    fun claudeResolveForTaskBackfillsFromDisk() {
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
            val task = AgentTask(
                id = "task-1",
                title = "t",
                prompt = "ship the navbar fix",
                agent = AgentKind.ClaudeCode,
                cwd = cwd,
                createdAtMillis = 1L,
            )
            val resolved = ClaudeSessionIds.resolveForTask(task, home)
            assertEquals(sessionId, resolved)
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
    fun codexFindsSessionByPromptInRollout() {
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
            val found = CodexSessionIds.findByPrompt("add dark mode toggle", cwd, home)
            assertEquals(sessionId, found)
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
            assertEquals(
                sessionId,
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
