package app.andy.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import app.andy.desktop.service.agents.AgentTerminalManager
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus

class JediTermScrollbackTest {
    @Test
    fun textStyleToAnsiIncludesBoldAndTruecolor() {
        val style = TextStyle(
            TerminalColor.rgb(228, 222, 208),
            TerminalColor.rgb(17, 16, 13),
            java.util.EnumSet.of(TextStyle.Option.BOLD),
        )
        val sgr = style.toAnsiSgr()
        assertTrue(sgr.startsWith("\u001b["))
        assertTrue(sgr.endsWith("m"))
        assertTrue(sgr.contains(";1;") || sgr.contains("[1;") || sgr.contains(";1m") || sgr.contains("1;"))
        assertTrue(sgr.contains("38;2;228;222;208"))
        assertTrue(sgr.contains("48;2;17;16;13"))
    }

    @Test
    fun capScrollbackSizeDropsOldestLines() {
        val content = (1..200).joinToString("") { idx -> "line-$idx-xxxxxxxx\n" }
        val capped = capScrollbackSize(content, maxBytes = 300)
        assertTrue(capped.toByteArray().size <= 300 + 40)
        assertTrue(capped.length < content.length)
        assertFalse(capped.contains("line-1-"), "oldest lines should be dropped")
        assertTrue(capped.contains("line-200-"), "newest lines should remain")
    }

    @Test
    fun exportScrollbackAnsiContainsEchoOutput() = runBlocking {
        val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
        val argv = if (isWindows) {
            listOf("cmd", "/c", "echo", "andy-scrollback-ok")
        } else {
            listOf("/bin/echo", "andy-scrollback-ok")
        }
        val session = TerminalSessions.create(
            TerminalLaunchRequest(
                sessionId = "scrollback-export-test",
                argv = argv,
            ),
        ) as JediTermBackend
        try {
            withTimeout(15_000) { session.exitCode.first { it != null } }
            delay(200) // let scrape/final paint settle
            val ansi = session.exportScrollbackAnsi()
            assertTrue(
                ansi.contains("andy-scrollback-ok"),
                "expected echo text in scrollback export, got=${ansi.take(200)}",
            )
            assertTrue(ansi.contains("\u001b[") || ansi.contains("andy-scrollback-ok"))
        } finally {
            session.close()
        }
    }

    @Test
    fun agentTerminalManagerPersistsAndAppendsScrollback() = runBlocking {
        val dir = File.createTempFile("andy-scrollback", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
            )
            val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
            val taskId = "scroll-task-1"
            val task = AgentTask(
                id = taskId,
                title = "scroll",
                agent = AgentKind.ClaudeCode,
                status = AgentTaskStatus.Running,
                prompt = "test",
                cwd = dir.absolutePath,
                createdAtMillis = System.currentTimeMillis(),
            )
            val argv1 = if (isWindows) {
                listOf("cmd", "/c", "echo", "first-run-output")
            } else {
                listOf("/bin/echo", "first-run-output")
            }
            manager.start(task, argv1, emptyMap())
            withTimeout(15_000) { manager.awaitExit(taskId) }
            manager.stop(taskId)

            val file = File(dir, "$taskId/scrollback.ansi")
            assertTrue(file.isFile, "scrollback file should exist after stop")
            val first = file.readText()
            assertTrue(first.contains("first-run-output"), "first run missing: ${first.take(300)}")

            val argv2 = if (isWindows) {
                listOf("cmd", "/c", "echo", "second-run-output")
            } else {
                listOf("/bin/echo", "second-run-output")
            }
            manager.start(task, argv2, emptyMap())
            withTimeout(15_000) { manager.awaitExit(taskId) }
            manager.stop(taskId)

            val second = file.readText()
            assertTrue(second.contains("first-run-output"), "cumulative should keep first run")
            assertTrue(second.contains("second-run-output"), "cumulative should append second run")
            assertTrue(
                second.contains("───"),
                "expected session separator between runs",
            )
            assertTrue(second.length > first.length, "appended scrollback should grow")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun hasScrollbackFalseWhenMissing() {
        val dir = File.createTempFile("andy-scrollback-missing", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
            )
            assertFalse(manager.hasScrollback("no-such-task"))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun ansiReplayConnectorServesAllBytesThenParks() {
        val connector = AnsiReplayTtyConnector("hi")
        val buf = CharArray(8)
        assertEquals(2, connector.read(buf, 0, 8))
        assertEquals('h', buf[0])
        assertEquals('i', buf[1])
        assertTrue(connector.isConnected)
        connector.close()
        assertEquals(-1, connector.read(buf, 0, 8))
        assertFalse(connector.isConnected)
    }
}
