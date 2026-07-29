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
            append("\u001b[?1049h\u001b[H")
            for (i in 1..300) append("Step $i: detail line\r\n")
        }
        val rows = replayCaptureStyledRows(raw)
        val plain = rows.joinToString("\n") { it.plain }
        for (i in listOf(1, 2, 150, 299, 300)) {
            assertTrue(plain.contains("Step $i:"), "missing Step $i, captured ${rows.size} rows")
        }
    }

    @Test
    fun replayCaptureStyledRowsSamplesShortLinesBeforeTheyOutrunTheReplayGrid() {
        AndyKetraTermConfig.ensureInitialized()
        // This stays well below the byte limit but exceeds the 200-row replay grid.
        val raw = buildString {
            append("\u001b[?1049h\u001b[H")
            for (i in 1..250) append("$i\r\n")
        }

        val rows = replayCaptureStyledRows(raw)
        val plain = rows.joinToString("\\n") { it.plain }

        assertTrue(rows.any { it.plain == "1" }, "first short row was lost: $plain")
        assertTrue(rows.any { it.plain == "250" }, "last short row was lost: $plain")
    }

    @Test
    fun scrollbackReplayCaptureProcessesOnlyNewTeeContent() {
        AndyKetraTermConfig.ensureInitialized()
        val replay = ScrollbackReplayCapture()
        try {
            val first = "first line\\r\\n"
            val second = "second line\\r\\n"
            assertTrue(
                replay.capture(ScrollbackAnsiSnapshot(first, 0L, first.length.toLong(), 0L))
                    .any { it.plain.contains("first line") },
            )
            val combined = first + second
            val rows = replay.capture(
                ScrollbackAnsiSnapshot(combined, 0L, combined.length.toLong(), 0L),
            )
            assertTrue(rows.any { it.plain.contains("first line") })
            assertTrue(rows.any { it.plain.contains("second line") })
        } finally {
            replay.close()
        }
    }

    @Test
    fun oneShotDerivationStitchesRepaintsAtLeastAsCleanlyAsIncrementalCapture() {
        AndyKetraTermConfig.ensureInitialized()
        // Persistence now mirrors raw PTY bytes and derives the transcript once, on demand,
        // instead of re-deriving it every 2s from whatever had arrived so far. Deriving in one
        // pass is strictly better: replayCaptureChunks keeps each full redraw atomic, whereas
        // a timer boundary could bisect one and leave the half-painted screen in history as a
        // duplicated window. Assert the contract (every section once, in order) and that the
        // incremental path is the one that duplicates.
        val raw = buildString {
            append("\u001b[?1049h")
            for (latestStep in 1..12) {
                append("\u001b[2J\u001b[H")
                for (step in maxOf(1, latestStep - 3)..latestStep) {
                    append("Step $step: section heading\r\n")
                    repeat(6) { line -> append("Step $step detail $line with enough text to matter.\r\n") }
                }
                append("────────────────────────────────\r\n")
                append("❯ Continue\r\n")
            }
        }

        val oneShot = replayCaptureStyledRows(raw).joinToString("\n") { it.plain }
        val headings = Regex("""Step (\d+): section heading""")
            .findAll(oneShot)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertEquals((1..12).toList(), headings, "one-shot derivation must keep each section once, in order")

        val incremental = ScrollbackReplayCapture().use { replay ->
            var rows = emptyList<StyledTerminalRow>()
            // Feed the stream the way a 2s timer did: a growing window cut at arbitrary bytes.
            var end = 0
            while (end < raw.length) {
                end = minOf(end + 900, raw.length)
                rows = replay.capture(ScrollbackAnsiSnapshot(raw.substring(0, end), 0L, end.toLong(), 0L))
            }
            rows.joinToString("\n") { it.plain }
        }
        val incrementalHeadings = Regex("""Step (\d+): section heading""").findAll(incremental).count()
        assertTrue(
            incrementalHeadings >= headings.size,
            "arbitrary feed boundaries should duplicate, not lose, windows",
        )
    }

    @Test
    fun replayCaptureStyledRowsKeepsEverySectionOfALongAlternateScreenAnswer() {
        AndyKetraTermConfig.ensureInitialized()
        // Claude/Codex-style TUIs use the alternate screen, so there is no native terminal
        // history once earlier rows scroll off. This is deliberately much longer than one
        // screen and includes repeated prose; both are normal in a detailed plan.
        val raw = buildString {
            append("\u001b[?1049h")
            for (step in 1..20) {
                append("Step $step: Coffee section\r\n")
                repeat(24) { line ->
                    // Compact rows deliberately make one old 8 KiB replay batch exceed the
                    // 200-row alternate-screen grid. That used to discard early sections.
                    append("S$step-$line: concise plan detail\r\n")
                }
            }
        }

        val rows = replayCaptureStyledRows(raw)
        val plain = rows.joinToString("\n") { it.plain }
        for (step in 1..20) {
            assertTrue(plain.contains("Step $step: Coffee section"), "missing Step $step")
        }
        assertEquals(
            20,
            Regex("Step \\d+: Coffee section").findAll(plain).count(),
            "replay must keep every distinct section in order",
        )
    }

    @Test
    fun replayCaptureStyledRowsStitchesRedrawnTuiWindowsIntoOneTranscript() {
        AndyKetraTermConfig.ensureInitialized()
        val raw = buildString {
            append("\u001b[?1049h")
            for (latestStep in 1..20) {
                // Model the full-screen repaint used by agent TUIs: only a moving five-step
                // window survives in the terminal, followed by fixed interactive chrome.
                append("\u001b[2J\u001b[H")
                for (step in maxOf(1, latestStep - 4)..latestStep) {
                    append("Step $step: Coffee section\r\n")
                    repeat(8) { paragraphLine ->
                        append("Step $step paragraph $paragraphLine explains the brewing detail in full.\r\n")
                    }
                }
                append("────────────────────────────────\r\n")
                append("❯ Continue\r\n")
            }
        }

        val rows = replayCaptureStyledRows(raw)
        val plain = rows.joinToString("\n") { it.plain }
        val headings = Regex("""Step (\d+): Coffee section""")
            .findAll(plain)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertEquals((1..20).toList(), headings)
        assertEquals(1, Regex("""^❯ Continue$""", RegexOption.MULTILINE).findAll(plain).count())
    }

    @Test
    fun replayCaptureStyledRowsDoesNotDropRepeatedText() {
        AndyKetraTermConfig.ensureInitialized()
        val raw = "\u001b[?1049h" + buildString {
            repeat(3) { append("same intentional line\r\n") }
        }

        val rows = replayCaptureStyledRows(raw)

        assertEquals(3, rows.count { it.plain == "same intentional line" })
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
    fun savedReplayRepairsRepeatedCodexStartupFramesWhenOpened() {
        val dir = File.createTempFile("andy-scrollback-header-repair", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val taskId = "repeated-startup"
            val boot = """
                ╭─────────────────────────────────────────────────╮
                │ >_ OpenAI Codex (v0.146.0-alpha.3.1)            │
                │ model:     gpt-5.6-luna high   /model to change │
                │ directory: ~/Code/Andy/Andy                     │
                ╰─────────────────────────────────────────────────╯
            """.trimIndent()
            val file = File(dir, "$taskId/scrollback.ansi").also {
                it.parentFile.mkdirs()
                it.writeText(
                    "$boot\n› build a detailed plan\n" +
                        "$boot\n› build a detailed plan\n" +
                        "$boot\n› build a detailed plan\n• Complete plan body",
                )
            }
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )

            val replay = manager.scrollbackReplayText(taskId)

            assertNotNull(replay)
            assertEquals(1, Regex("OpenAI Codex").findAll(replay).count(), replay)
            assertEquals(1, Regex("build a detailed plan").findAll(replay).count(), replay)
            assertTrue(replay.contains("Complete plan body"), replay)
            assertTrue(file.isFile)
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
