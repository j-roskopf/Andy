package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentSessionStatus
import app.andy.terminal.TerminalSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.io.File

class AgentStatusTrackerTest {
    @Test
    fun parseStatusJsonMapsBlocked() {
        assertEquals(AgentSessionStatus.Blocked, parseStatusJson("""{"status":"blocked"}"""))
    }

    @Test
    fun parseStatusJsonMapsDoneWorkingAndIdle() {
        assertEquals(AgentSessionStatus.Done, parseStatusJson("""{"status":"done"}"""))
        assertEquals(AgentSessionStatus.Working, parseStatusJson("""{"status":"working"}"""))
        assertEquals(AgentSessionStatus.Idle, parseStatusJson("""{"status":"idle"}"""))
    }

    @Test
    fun claudeScrapeRulesMatchApprovalPrompt() {
        val rules = scrapeRulesFor(AgentKind.ClaudeCode)
        assertTrue(rules.blocked.any { it.containsMatchIn("Do you want to proceed?") })
    }

    @Test
    fun claudeScrapeRulesMatchTrustAndBypassDialogs() {
        val rules = scrapeRulesFor(AgentKind.ClaudeCode)
        assertTrue(rules.blocked.any { it.containsMatchIn("Quick safety check: Is this a project you trust?") })
        assertTrue(rules.blocked.any { it.containsMatchIn("1. No, exit") })
    }

    @Test
    fun claudeScrapeRulesMatchPerambulatingSpinner() {
        val rules = scrapeRulesFor(AgentKind.ClaudeCode)
        val tail = "✨ Perambulating... (33s · ↓ 547 tokens · thinking more)\n> "
        assertTrue(rules.working.any { it.containsMatchIn(tail) })
        assertTrue(bufferLooksWorking(AgentKind.ClaudeCode, tail))
    }

    @Test
    fun perambulatingBufferStaysWorkingAfterQuiescence() {
        val scrape = ScrapeStatusSource(AgentKind.ClaudeCode)
        scrape.onBuffer("✨ Perambulating... (33s · ↓ 547 tokens · thinking more)\n> ")
        scrape.tickQuiescence(idleAfterMs = 0)
        assertEquals(AgentSessionStatus.Working, scrape.latest())
        assertFalse(scrape.isQuiescentAtPrompt(idleAfterMs = 0))
    }

    @Test
    fun staleHookDoneDoesNotOverridePerambulatingScrape() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            File(artifactDir, "status.json").writeText("""{"status":"done","at":1}""")
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-status",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                isTabSeen = { true },
            )
            tracker.start()
            session.emitBuffer("✨ Perambulating... (33s · ↓ 547 tokens · thinking more)\n> ")
            kotlinx.coroutines.delay(600)
            assertEquals(AgentSessionStatus.Working, tracker.status.value)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bufferLooksBlockedIgnoresStalePermissionMentionsOutsideTail() {
        val stalePermission = "x".repeat(900) + "checking file permissions"
        val activeTail = stalePermission + "\n" + "Reading src/main.kt...\n".repeat(20)
        assertEquals(false, bufferLooksBlocked(AgentKind.ClaudeCode, activeTail))
    }

    @Test
    fun readLatestHookStatusUsesLastLineOnly() {
        val dir = File.createTempFile("andy-hook", null).also { it.delete(); it.mkdirs() }
        try {
            val statusFile = File(dir, "status.json")
            statusFile.writeText(
                """
                {"status":"blocked","at":1}
                {"status":"working","at":2}
                """.trimIndent(),
            )
            assertEquals(AgentSessionStatus.Working, readLatestHookStatus(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readLatestHookStatusIgnoresNotificationBlockedAfterDone() {
        val dir = File.createTempFile("andy-hook", null).also { it.delete(); it.mkdirs() }
        try {
            File(dir, "status.json").writeText(
                """
                {"status":"done","at":1}
                {"status":"blocked","at":2}
                """.trimIndent() + "\n",
            )
            assertEquals(AgentSessionStatus.Done, readLatestHookStatus(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun activelyWorkingOverridesStaleBlockedHook() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() }
            File(artifactDir, "status.json").writeText("""{"status":"blocked","at":1}""")
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-status",
                agent = AgentKind.ClaudeCode,
                artifactDir = artifactDir,
                session = session,
                isTabSeen = { false },
            )
            tracker.start()
            kotlinx.coroutines.delay(500)
            session.emitBuffer("Analyzing codebase...\n".repeat(30))
            kotlinx.coroutines.delay(600)
            assertEquals(AgentSessionStatus.Working, tracker.status.value)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun installClaudeStatusHooksWritesValidJsonOutsideHome() {
        val home = File.createTempFile("andy-home", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val cwd = File(home, "project").also { it.mkdirs() }
            val artifacts = File(cwd, ".andy/task-hooks").also { it.mkdirs() }
            installClaudeStatusHooks(cwd, artifacts)

            val settings = File(cwd, ".claude/settings.json")
            assertTrue(settings.isFile)
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(settings.readText()).jsonObject
            assertTrue(parsed.containsKey("hooks"))
            assertTrue(File(artifacts, "andy-status-hook.sh").canExecute())

            // Must not touch global ~/.claude under the temp home.
            assertTrue(!File(home, ".claude/settings.json").exists())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun installClaudeStatusHooksSkipsWhenCwdIsHome() {
        val home = File.createTempFile("andy-home-skip", null).also { it.delete(); it.mkdirs() }
        val previousHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val artifacts = File(home, ".andy/task-hooks").also { it.mkdirs() }
            installClaudeStatusHooks(home, artifacts)
            assertTrue(!File(home, ".claude/settings.json").exists())
        } finally {
            System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun isQuiescentAtPromptDetectsClaudePrompt() {
        val scrape = ScrapeStatusSource(AgentKind.ClaudeCode)
        scrape.onBuffer("Here is the answer about Pty4j and KetraTerm.\n✻ Cooked for 8s\n> ")
        assertTrue(scrape.isQuiescentAtPrompt(idleAfterMs = 0))
    }

    @Test
    fun scrapeStatusSourceDetectsBlockedThenIdleAfterQuiescence() = runTest {
        val scrape = ScrapeStatusSource(AgentKind.ClaudeCode)
        scrape.onBuffer("Some output\nDo you want to proceed? (y/n)")
        assertEquals(AgentSessionStatus.Blocked, scrape.latest())

        scrape.onBuffer("Ready at prompt\n> ")
        scrape.tickQuiescence(idleAfterMs = 0)
        assertEquals(AgentSessionStatus.Idle, scrape.latest())
    }

    @Test
    fun hookBlockedIgnoredWhenBufferSettledAtPrompt() {
        val scrape = ScrapeStatusSource(AgentKind.ClaudeCode)
        scrape.onBuffer("Fixed. Here's the summary of the change.\n✻ Brewed for 11m 47s\n> ")
        scrape.tickQuiescence(idleAfterMs = 0)
        assertEquals(AgentSessionStatus.Idle, scrape.latest())
        assertFalse(scrape.isCurrentlyBlocked())
    }

    @Test
    fun agentStatusTrackerPrefersBlockedFromScrape() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = FakeTerminalSession()
            val tracker = AgentStatusTracker(
                scope = scope,
                taskId = "task-status",
                agent = AgentKind.ClaudeCode,
                artifactDir = File.createTempFile("andy-status", null).also { it.delete(); it.mkdirs() },
                session = session,
                isTabSeen = { false },
            )
            tracker.start()
            session.emitBuffer("Allow this action? (y/n)")
            kotlinx.coroutines.delay(600)
            assertEquals(AgentSessionStatus.Blocked, tracker.status.value)
            tracker.close()
        } finally {
            scope.cancel()
        }
    }
}

private class FakeTerminalSession : TerminalSession {
    override val sessionId: String = "fake"
    override val isAlive: Boolean = true
    override val exitCode: StateFlow<Int?> = MutableStateFlow(null)
    override val pid: Long? = null

    private val snapshots = MutableSharedFlow<String>(extraBufferCapacity = 8, replay = 1)
    override val bufferSnapshots: SharedFlow<String> = snapshots

    override fun start(argv: List<String>, cwd: String?, env: Map<String, String>) = Unit
    override fun write(bytes: ByteArray) = Unit
    override fun resize(cols: Int, rows: Int) = Unit
    override fun bufferSnapshot(): String = ""
    override fun close() = Unit

    suspend fun emitBuffer(text: String) {
        snapshots.emit(text)
    }
}
