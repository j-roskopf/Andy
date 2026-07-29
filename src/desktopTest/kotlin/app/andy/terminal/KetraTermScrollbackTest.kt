package app.andy.terminal

import app.andy.desktop.service.agents.AgentTerminalManager
import app.andy.desktop.service.agents.AgentTerminalMode
import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.AgentStatus
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalThemePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun looksLikeRawAnsiTeeDetectsPtyStream() {
        assertFalse(looksLikeRawAnsiTee("plain\n"))
        assertFalse(looksLikeRawAnsiTee("hello\n"))
        assertTrue(looksLikeRawAnsiTee("\u001b[2A\u001b[0G".repeat(10)))
    }

    @Test
    fun looksLikeRawAnsiTeeAcceptsStyledTranscripts() {
        // Persisted history is SGR-only; it must replay verbatim, not get flattened.
        assertFalse(looksLikeRawAnsiTee("\u001b[38;5;39mhello\u001b[0m\n\u001b[1mbold\u001b[0m\n"))
        assertFalse(looksLikeRawAnsiTee("\u001b[0m╭──────╮\u001b[0m\n\u001b[0m│ hi   │\u001b[0m\n"))
    }

    @Test
    fun scrollbackReplayColumnsFitWidestVisibleRow() {
        val content = "\u001b[32m" + "x".repeat(180) + "\u001b[0m\nshort\n"
        assertTrue(scrollbackReplayColumns(content) >= 181)
        assertEquals(100, scrollbackReplayColumns("tiny\n"))
        assertEquals(120, scrollbackReplayColumns("y".repeat(500), maxColumns = 120))
    }

    @Test
    fun resolveScrollbackForReplayCollapsesSpinnerRedraws() {
        AndyKetraTermConfig.ensureInitialized()
        val noisy = buildString {
            repeat(40) {
                append("\r\u001b[2A\u001b[0G⣾  \u001b[90mWorking\u001b[0m")
            }
            append("\nFinal answer is ready.\n> ")
        }
        val resolved = resolveScrollbackForReplay(noisy)
        assertTrue(resolved.contains("Final answer is ready."), "resolved=$resolved")
        val workingCount = Regex("Working").findAll(resolved).count()
        assertTrue(workingCount <= 1, "expected at most one Working line, got $workingCount in:\n$resolved")
    }

    @Test
    fun resolveScrollbackForReplayRepairsLegacyCursorTuiSnippet() {
        AndyKetraTermConfig.ensureInitialized()
        val legacy = buildString {
            repeat(25) {
                append("\u001b[?25l\r\u001b[2A\u001b[0G⣾  \u001b[90mWorking\u001b[0m")
                append("\u001b[93D\u001b[?25h\u001b[?25l")
            }
            append("\r\n\u001b[90m─── ───\u001b[0m\r\n")
            append("iOS Simulator mirroring is unchanged (no camera permission involved)\n")
            append("> ")
        }
        val resolved = resolveScrollbackForReplay(legacy)
        assertTrue(resolved.contains("iOS Simulator mirroring is unchanged"))
        assertTrue(Regex("Working").findAll(resolved).count() <= 1)
        assertFalse(looksLikeRawAnsiTee(resolved))
    }

    @Test
    fun resolveScrollbackForReplayRepairsRealCursorAgentFile() {
        AndyKetraTermConfig.ensureInitialized()
        val file = java.io.File(System.getProperty("user.home"), ".andy/agents/task-7a20497d5e/scrollback.ansi")
        if (!file.isFile) return
        val raw = file.readText()
        val resolved = resolveScrollbackForReplay(raw)
        assertTrue(
            resolved.contains("would it auto apply to old chats") ||
                resolved.contains("readable on the next open"),
            "resolved should keep conversation text",
        )
        assertFalse(looksLikeRawAnsiTee(resolved))
        val spinnerStatusCount = Regex("""[⠀-⣿].*\b(Working|Running|Thinking)\b""").findAll(resolved).count()
        assertTrue(spinnerStatusCount == 0, "expected no spinner status lines, got $spinnerStatusCount")
    }

    @Test
    fun replayCaptureStyledRowsKeepsContentLongerThanTheReplayGrid() {
        AndyKetraTermConfig.ensureInitialized()
        // More lines than the replay buffer is tall (REPLAY_ROWS = 200): if capture only
        // read the final screen, everything before the last ~200 lines would be gone —
        // exactly the "fast model outdistances any poll" loss this function exists to fix.
        val raw = buildString {
            for (i in 1..300) append("Step $i: detail line\r\n")
        }
        val rows = replayCaptureStyledRows(raw)
        val plain = rows.joinToString("\n") { it.plain }
        for (i in listOf(1, 2, 150, 299, 300)) {
            assertTrue(plain.contains("Step $i:"), "missing Step $i, captured ${rows.size} rows")
        }
    }

    @Test
    fun replayCaptureStyledRowsPreservesStyling() {
        AndyKetraTermConfig.ensureInitialized()
        val raw = "[31mred line[0m\r\n"
        val rows = replayCaptureStyledRows(raw)
        assertTrue(rows.any { it.plain.contains("red line") })
        assertTrue(rows.any { it.ansi.contains("[") }, "expected styling kept in the ansi field")
    }

    @Test
    fun agentTerminalManagerPersistsResolvedScrollbackNotRawTee() = runBlocking {
        val dir = File.createTempFile("andy-scrollback", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
            val taskId = "scroll-task-1"
            val task = AgentTask(
                id = taskId,
                title = "scroll",
                agent = AgentKind.ClaudeCode,
                status = AgentStatus.Working,
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
            awaitScrollbackContains(file, "first-run-output")
            val first = file.readText()
            assertFalse(looksLikeRawAnsiTee(first), "persisted scrollback should be resolved text")

            val argv2 = if (isWindows) {
                listOf("cmd", "/c", "echo", "second-run-output")
            } else {
                listOf("/bin/echo", "second-run-output")
            }
            manager.start(task, argv2, emptyMap())
            withTimeout(15_000) { manager.awaitExit(taskId) }
            manager.stop(taskId)

            awaitScrollbackContains(file, "second-run-output")
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
    fun persistedScrollbackKeepsTerminalStyling() = runBlocking {
        if (System.getProperty("os.name").contains("windows", ignoreCase = true)) return@runBlocking
        val dir = File.createTempFile("andy-scrollback-styled", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            val taskId = "styled-task"
            val task = AgentTask(
                id = taskId,
                title = "styled",
                agent = AgentKind.ClaudeCode,
                status = AgentStatus.Working,
                prompt = "test",
                cwd = dir.absolutePath,
                createdAtMillis = System.currentTimeMillis(),
            )
            manager.start(
                task,
                listOf("/bin/sh", "-c", "printf '\\033[31mred-line\\033[0m\\n    indented-line\\n'"),
                emptyMap(),
            )
            withTimeout(15_000) { manager.awaitExit(taskId) }
            manager.stop(taskId)

            val file = File(dir, "$taskId/scrollback.ansi")
            awaitScrollbackContains(file, "red-line")
            val replay = manager.scrollbackReplayText(taskId)
            assertNotNull(replay)
            assertTrue(replay.contains("red-line"), "missing output: ${replay.take(300)}")
            assertTrue(replay.contains("\u001b["), "styling was stripped: ${replay.take(300)}")
            assertTrue(
                replay.lines().any { it.contains("    indented-line") },
                "indentation was trimmed: ${replay.take(300)}",
            )
            assertFalse(replay.contains("\n\n\n"), "replay should not be re-spaced")
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
                mode = AgentTerminalMode.DirectPty,
            )
            assertFalse(manager.hasScrollback("no-such-task"))
            assertNull(manager.openScrollbackReplay("no-such-task"))
            assertNull(manager.scrollbackReplayText("no-such-task"))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun styledHistoryReplaysVerbatim() {
        val dir = File.createTempFile("andy-scrollback-flush", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val taskId = "flush-task"
            val saved = "\u001b[36m> earlier user prompt\u001b[0m\n    \u001b[32massistant reply line\u001b[0m"
            File(dir, "$taskId/scrollback.ansi").also { file ->
                file.parentFile.mkdirs()
                file.writeText("$saved\n")
            }
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            assertEquals(saved, manager.scrollbackReplayText(taskId))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun openScrollbackReplayBuildsViewer() {
        val dir = File.createTempFile("andy-scrollback-replay", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val taskId = "replay-task"
            val scrollback = File(dir, "$taskId/scrollback.ansi").also { file ->
                file.parentFile.mkdirs()
                file.writeText("hello from finished chat\r\n")
            }
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            assertTrue(manager.hasScrollback(taskId))
            assertTrue(scrollback.isFile)
            val widget = manager.openScrollbackReplay(taskId)
            assertNotNull(widget)
            // READ-ONLY history must not take keyboard focus / accept typing.
            assertFalse(widget.isFocusable)
            assertTrue(widget.mouseWheelListeners.isNotEmpty(), "scrollback replay must handle wheel without focus")
            disposeScrollbackReplayTerminal(widget)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun replayGridIsAsWideAsTheLiveAlternateScreen() {
        val appearance = TerminalAppearanceSnapshot()
        // Sweep widths: the normal buffer's extra chrome only costs a column at some sizes,
        // and one lost column is enough to wrap the right border off every saved row.
        for (width in 900..1400 step 37) {
            val pixels = java.awt.Dimension(width, 640)
            val live = altScreenGridSize(appearance, pixels)
            val replay = createScrollbackReplayTerminal("history", cols = 120, rows = 32, appearance = appearance)
            try {
                val grid = onSwingEdt {
                    replay.setSize(pixels)
                    replay.doLayout()
                    replay.visibleGridSize()
                }
                assertEquals(
                    live.width,
                    grid.width,
                    "replay lost columns to normal-buffer chrome at ${pixels.width}px, so saved rows wrap",
                )
            } finally {
                disposeScrollbackReplayTerminal(replay)
            }
        }
    }

    /** Grid an agent CLI draws into: a live terminal on the alternate screen. */
    private fun altScreenGridSize(
        appearance: TerminalAppearanceSnapshot,
        pixels: java.awt.Dimension,
    ): java.awt.Dimension = onSwingEdt {
        val buffer = io.github.ketraterm.core.TerminalBuffers.create(width = 120, height = 32, maxHistory = 100)
        val session = io.github.ketraterm.session.TerminalSession.create(
            terminal = buffer,
            connector = ParkedTerminalConnector(),
        )
        session.start(120, 32)
        val altScreen = "\u001b[?1049h".toByteArray()
        session.onBytes(altScreen, 0, altScreen.size)
        val widget = io.github.ketraterm.ui.swing.api.SwingTerminal(
            settingsProvider = { appearance.toSwingSettings(columns = 120, rows = 32) },
        )
        widget.bind(session)
        widget.setSize(pixels)
        widget.doLayout()
        widget.visibleGridSize().also {
            widget.dispose()
            session.close()
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

    private suspend fun awaitScrollbackContains(file: File, text: String, timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) {
            while (true) {
                if (file.isFile && file.readText().contains(text)) return@withTimeout
                delay(100)
            }
        }
    }
}
