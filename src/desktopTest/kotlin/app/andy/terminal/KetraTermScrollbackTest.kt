package app.andy.terminal

import app.andy.desktop.service.agents.AgentTerminalManager
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentTaskStatus
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalThemePreset
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
import java.nio.file.Files

class KetraTermScrollbackTest {
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
    fun scrollbackTeeCapturesStdoutBytes() {
        val tee = ScrollbackAnsiTee(maxBytes = 1024)
        val chunk = "hello-\u001b[32mgreen\u001b[0m\n".encodeToByteArray()
        tee.append(chunk, 0, chunk.size)
        val snap = tee.snapshot()
        assertTrue(snap.contains("hello-"))
        assertTrue(snap.contains("\u001b[32m"))
    }

    @Test
    fun exportScrollbackAnsiContainsEchoOutput() = runBlocking {
        AndyKetraTermConfig.ensureInitialized()
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
        ) as KetraTermBackend
        try {
            withTimeout(15_000) { session.exitCode.first { it != null } }
            delay(200)
            val ansi = session.scrollbackAnsi()
            assertTrue(
                ansi.contains("andy-scrollback-ok"),
                "expected echo text in scrollback export, got=${ansi.take(200)}",
            )
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
    fun appearanceMapsToSwingSettings() {
        val settings = TerminalAppearanceSnapshot(
            ketraThemeId = TerminalThemePreset.Nord.id,
            fontSize = 16f,
        ).toSwingSettings(columns = 80, rows = 24)
        assertEquals(80, settings.columns)
        assertEquals(24, settings.rows)
        assertEquals(16, settings.font.size)
    }

    @Test
    fun configForceEnablesHistoryAndNotificationsUnderAndyHome() {
        val previousHome = System.getProperty("user.home")
        val previousConfig = System.getProperty("ketraterm.config.path")
        val tempHome = Files.createTempDirectory("andy-ketraterm-home")
        try {
            System.setProperty("user.home", tempHome.toString())
            AndyKetraTermConfig.resetForTests()
            AndyKetraTermConfig.ensureInitialized()
            val configPath = AndyKetraTermPaths.configFile()
            assertTrue(Files.isRegularFile(configPath), "expected config at $configPath")
            assertTrue(configPath.toString().contains(".andy${File.separator}ketraterm") ||
                configPath.toString().contains(".andy/ketraterm"))
            assertEquals(
                configPath.toAbsolutePath().toString(),
                System.getProperty("ketraterm.config.path"),
            )
            val reloaded = io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager(configPath).load()
            assertTrue(reloaded.desktopNotificationsEnabled)
            assertTrue(reloaded.persistentCommandHistoryEnabled)
            assertEquals(AndyKetraTermPaths.commandHistoryFile(), AndyKetraTermPaths.root().resolve("command-history-v1.tsv"))
        } finally {
            AndyKetraTermConfig.resetForTests()
            if (previousHome != null) {
                System.setProperty("user.home", previousHome)
            } else {
                System.clearProperty("user.home")
            }
            if (previousConfig != null) {
                System.setProperty("ketraterm.config.path", previousConfig)
            } else {
                System.clearProperty("ketraterm.config.path")
            }
            tempHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun ansiReplayConnectorServesAllBytesThenParks() {
        val payload = "hi".encodeToByteArray()
        val connector = AnsiReplayConnector(payload)
        val received = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        val closed = java.util.concurrent.CountDownLatch(1)
        connector.start(
            object : io.github.ketraterm.transport.TerminalConnectorListener {
                override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
                    received.set(bytes.copyOfRange(offset, offset + length))
                }

                override fun onClosed(exitCode: Int?) {
                    closed.countDown()
                }

                override fun onError(error: Throwable) = Unit
            },
        )
        // Allow reader thread to feed bytes.
        Thread.sleep(50)
        assertEquals("hi", received.get()?.decodeToString())
        connector.close()
        assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
}
