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

class BossTermScrollbackTest {
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
    fun scrollbackTeeCopiesOnlyContentAfterConsumerCursor() {
        val tee = ScrollbackAnsiTee(maxBytes = 1024)
        "first".encodeToByteArray().let { tee.append(it, 0, it.size) }
        val first = tee.snapshotWithOffsets()

        "-second".encodeToByteArray().let { tee.append(it, 0, it.size) }
        val delta = tee.snapshotWithOffsets(
            ScrollbackAnsiCursor(offset = first.endOffset, epoch = first.epoch),
        )

        assertEquals("-second", delta.content)
        assertEquals(first.endOffset, delta.startOffset)
        assertEquals(first.epoch, delta.epoch)
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
        ) as BossTermBackend
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
    fun legacyTmuxCopyModeFramesAreExcludedFromRawHistory() {
        val realOutput = "answer line one\r\nanswer line two\r\n"
        val copyMode = buildString {
            append("\u001B[30m\u001B[43m13:21 [10/97]")
            append("\u001B[Hhistorical viewport ten")
            append("\u001B[30m\u001B[43m13:21 [9/97]")
            append("\u001B[Hhistorical viewport nine")
        }

        assertEquals(realOutput.trimEnd(), trimLegacyTmuxCopyModeOutput(realOutput + copyMode))
        assertEquals(realOutput, trimLegacyTmuxCopyModeOutput(realOutput))
    }

    @Test
    fun historicalRawCopyModeRecordingIsRepairedAndCommittedOnce() {
        val dir = File.createTempFile("andy-tmux-copy-mode-repair", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val taskId = "copy-mode-history"
            val taskDir = File(dir, taskId).also { it.mkdirs() }
            val ansi = File(taskDir, "scrollback.ansi").also {
                it.writeText("stale startup\n")
            }
            val raw = File(taskDir, "scrollback.raw").also {
                it.writeText(
                    "final answer line one\r\nfinal answer line two\r\n" +
                        "\u001B[30m\u001B[43m13:21 [97/97]" +
                        "\u001B[Hduplicated historical viewport",
                )
            }
            ansi.setLastModified(raw.lastModified() - 2_000)
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )

            val replay = assertNotNull(manager.scrollbackReplayText(taskId))

            assertTrue(replay.contains("final answer line one"), replay)
            assertFalse(replay.contains("97/97"), replay)
            assertFalse(replay.contains("duplicated historical viewport"), replay)
            assertTrue(ansi.readText().contains("final answer line two"), ansi.readText())
            assertTrue(ansi.lastModified() >= raw.lastModified())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
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
    fun infersLegacyLiveGridFromAbsoluteCursorAddressing() {
        // The broken history screenshot came from a 164x54 live terminal replayed into the
        // old 120-column default. Its provider addressed column 163 then painted two cells.
        val raw = "\u001B[54;1H\u001B[163Gto"

        assertEquals(ScrollbackGridSize(columns = 164, rows = 54), inferScrollbackGridSize(raw))
    }

    @Test
    fun replayUsesRecordedLiveGridForRightEdgeContent() {
        val raw = buildString {
            append(scrollbackLayoutMarker(columns = 164, rows = 54))
            append("\u001B[2J\u001B[54;163Hok")
        }

        val rows = replayCaptureStyledRows(raw)
        val rightEdge = assertNotNull(rows.firstOrNull { it.plain.trim() == "ok" })

        assertEquals(164, rightEdge.plain.length)
        assertTrue(rightEdge.plain.endsWith("ok"))
    }

    @Test
    fun scrollbackReplayCaptureProcessesOnlyNewTeeContent() {
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
        val raw = "\u001b[?1049h" + buildString {
            repeat(3) { append("same intentional line\r\n") }
        }

        val rows = replayCaptureStyledRows(raw)

        assertEquals(3, rows.count { it.plain == "same intentional line" })
    }

    @Test
    fun replayCaptureStyledRowsPreservesStyling() {
        val raw = "\u001b[31mred line\u001b[0m\r\n"
        val rows = replayCaptureStyledRows(raw)
        val styled = assertNotNull(rows.firstOrNull { it.plain.contains("red line") })
        assertEquals("red line", styled.plain.trim())
        assertTrue(
            styled.ansi.contains("\u001b[") && styled.ansi.contains("red line"),
            "ansi should keep SGR around the glyph, got=${styled.ansi}",
        )
        assertFalse(styled.plain.contains("\u001b"), "plain must be stripped")
    }

    @Test
    fun looksLikeBrokenPlainScrollbackDetectsDuplicatedBanners() {
        val duped = (1..20).joinToString("\n") { "Welcome to the Antigravity CLI. You are currently not signed in." }
        assertTrue(looksLikeBrokenPlainScrollback(duped))
        assertFalse(looksLikeBrokenPlainScrollback("\u001b[31monce\u001b[0m\nunique line here\n"))
    }

    @Test
    fun collapseRepeatedScrollbackLinesKeepsOneCopy() {
        val input = "a\na\na\nb\nb\nc\n"
        assertEquals("a\nb\nc", collapseRepeatedScrollbackLines(input))
    }

    @Test
    fun scrollbackReplayRepairsBrokenAnsiFromRawOnce() = runBlocking {
        val dir = File.createTempFile("andy-scrollback-repair", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val taskId = "repair-task"
            val taskDir = File(dir, taskId).also { it.mkdirs() }
            val raw = File(taskDir, "scrollback.raw")
            val ansi = File(taskDir, "scrollback.ansi")
            // Raw tee with real SGR; derive should keep color and a single banner.
            raw.writeText(
                buildString {
                    append(scrollbackLayoutMarker(columns = 80, rows = 24))
                    append("\u001b[31mWelcome to the Antigravity CLI. You are currently not signed in.\u001b[0m\r\n")
                    append("unique follow-up line\r\n")
                },
            )
            // Broken plain/duplicated .ansi that is fresher than raw so open prefers the file.
            val banner = "Welcome to the Antigravity CLI. You are currently not signed in."
            ansi.writeText((1..12).joinToString("\n") { banner } + "\nunique follow-up line\n")
            ansi.setLastModified(raw.lastModified() + 2_000)

            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            val first = assertNotNull(manager.scrollbackReplayText(taskId))
            assertEquals(1, Regex(Regex.escape(banner)).findAll(first).count(), first.take(400))
            assertTrue(first.contains("unique follow-up line"), first.take(400))
            assertTrue(first.contains("\u001b["), "repaired transcript should keep SGR")
            val rewritten = ansi.readText()
            assertTrue(rewritten.contains("\u001b["), "disk .ansi should be rewritten with SGR")
            assertFalse(looksLikeBrokenPlainScrollback(rewritten))

            val second = assertNotNull(manager.scrollbackReplayText(taskId))
            assertEquals(first, second)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun derivedRawReplayTextCachesSecondOpen() = runBlocking {
        val dir = File.createTempFile("andy-scrollback-cache", null).also {
            it.delete()
            it.mkdirs()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val taskId = "cache-task"
            val taskDir = File(dir, taskId).also { it.mkdirs() }
            File(taskDir, "scrollback.raw").writeText(
                "\u001b[32mcached-green\u001b[0m\r\nsecond-line\r\n",
            )
            // No committed .ansi => open derives from raw and caches.
            val manager = AgentTerminalManager(
                scope = scope,
                scrollbackFile = { id -> File(dir, "$id/scrollback.ansi") },
                mode = AgentTerminalMode.DirectPty,
            )
            val first = assertNotNull(manager.scrollbackReplayText(taskId))
            assertTrue(first.contains("cached-green"), first.take(300))
            val second = assertNotNull(manager.scrollbackReplayText(taskId))
            assertEquals(first, second)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
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
            val saved = file.readText()
            assertTrue(
                saved.contains("\u001b[") && saved.contains("red-line"),
                "new sessions should persist SGR into scrollback.ansi: ${saved.take(300)}",
            )
            val replay = manager.scrollbackReplayText(taskId)
            assertNotNull(replay)
            assertTrue(replay.contains("red-line"), "missing output: ${replay.take(300)}")
            assertTrue(replay.contains("\u001b["), "replay should keep SGR")
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
            val view = manager.openScrollbackReplay(taskId)
            assertNotNull(view)
            assertTrue(view.readOnly, "scrollback replay must be read-only")
            disposeScrollbackReplayView(view)
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
    fun appearanceMapsToBossTermSettings() {
        val settings = TerminalAppearanceSnapshot(
            ketraThemeId = TerminalThemePreset.Nord.id,
            fontSize = 16f,
        ).toBossTermSettingsOverride()
        assertEquals(16f, settings.fontSize)
        assertEquals("nord", settings.activeThemeId)
        assertEquals(true, settings.enableMouseReporting)
        assertEquals(true, settings.simulateMouseScrollInAlternateScreen)

        val agent = TerminalAppearanceSnapshot().toBossTermSettingsOverride(agentCliMode = true)
        assertEquals(false, agent.enableMouseReporting)
        assertEquals(true, agent.forceActionOnMouseReporting)
        assertEquals(true, agent.simulateMouseScrollInAlternateScreen)
        assertEquals(true, agent.scrollbarAlwaysVisible)

        val tmuxAttach = TerminalAppearanceSnapshot().toBossTermSettingsOverride(
            agentCliMode = true,
            forwardMouseToApplication = true,
        )
        assertEquals(true, tmuxAttach.enableMouseReporting)
        assertEquals(false, tmuxAttach.forceActionOnMouseReporting)
        assertEquals(0f, tmuxAttach.mouseScrollThreshold)
        assertEquals(false, tmuxAttach.simulateMouseScrollInAlternateScreen)
    }

    @Test
    fun createScrollbackReplayViewIsReadOnly() {
        val view = createScrollbackReplayView("hello from finished chat\n")
        try {
            assertTrue(view.readOnly)
        } finally {
            disposeScrollbackReplayView(view)
        }
    }

    @Test
    fun inferScrollbackGridSizePrefersCupEnvelopeOverStaleSmallMarker() {
        val raw = buildString {
            append(scrollbackLayoutMarker(columns = 120, rows = 32))
            append("\u001b[?1049h")
            append("\u001b[50;146Hok")
        }
        // CUP col 146 + 2-cell payload => envelope width 147; rows from CUP row 50.
        assertEquals(ScrollbackGridSize(columns = 147, rows = 50), inferScrollbackGridSize(raw))
    }

    @Test
    fun replayCaptureStyledRowsDoesNotDuplicateHomeRepaintsOnTallTui() {
        // Stale 120x32 marker + a 50-row home-repaint TUI used to emit the greeting hundreds
        // of times. The stream-wide CUP envelope must win so merge sees one screen.
        val raw = buildString {
            append(scrollbackLayoutMarker(columns = 120, rows = 32))
            append("\u001b[?1049h")
            repeat(40) {
                append("\u001b[H")
                for (row in 1..50) {
                    append("\u001b[${row};1H")
                    append("row-$row stable greeting line\u001b[K")
                }
            }
            append("\u001b[50;1Hfinal answer ready\u001b[K")
        }
        val rows = replayCaptureStyledRows(raw)
        val plain = rows.joinToString("\n") { it.plain }
        val greetingCount = Regex("stable greeting line").findAll(plain).count()
        assertTrue(
            greetingCount in 1..60,
            "expected one screen of greetings, got $greetingCount in ${rows.size} rows",
        )
        assertTrue(plain.contains("final answer ready"), plain.take(500))
    }

    @Test
    fun replayCaptureStyledRowsDedupsRealAntigravityHomeRepaintStream() {
        val file = java.io.File(System.getProperty("user.home"), ".andy/agents/task-54c06066a4/scrollback.raw")
        if (!file.isFile || file.length() < 100_000L) return
        val rows = replayCaptureStyledRows(file.readText())
        val plain = rows.joinToString("\n") { it.plain }
        val hello = Regex("Hello! How can I help you today with your project\\?").findAll(plain).count()
        assertTrue(
            hello in 1..3,
            "expected greeting once (or a couple after resumes), got $hello in ${rows.size} rows",
        )
        assertTrue(rows.size < 2_000, "expected compact transcript, got ${rows.size} rows")
    }

    @Test
    fun combineCommittedAndDerivedCollapsesPartialBootPrefix() {
        val committed = """
            ▄▀▀▄
            Welcome to the Antigravity CLI. You are currently not signed in.
              Signing in...
        """.trimIndent()
        val derived = """
            ▄▀▀▄
            Welcome to the Antigravity CLI.
            Antigravity CLI 1.1.9
            user@example.com
            > analyzer the current code base
            Analyzing Feature Gaps
        """.trimIndent()
        val combined = compactRepeatedProviderStartupText(
            combineCommittedAndDerivedScrollback(committed, derived),
        )
        assertEquals(1, Regex("Welcome to the Antigravity CLI").findAll(combined).count(), combined)
        assertTrue(combined.contains("Analyzing Feature Gaps"), combined)
        assertTrue(combined.contains("analyzer the current code base"), combined)
        assertFalse(combined.contains("Signing in"), combined)
    }

    @Test
    fun homeRepaintMergeReplacesViewportInsteadOfAppending() {
        val acc = ScrollbackAccumulator()
        val screen1 = (1..20).map { idx ->
            val text = if (idx == 1) "Analyzing Feature Gaps" else "section-a-line-$idx"
            StyledTerminalRow(text, text)
        }
        val screen2 = (1..20).map { idx ->
            val text = when (idx) {
                1 -> "Analyzing Feature Gaps"
                in 2..10 -> "section-a-line-$idx"
                else -> "section-b-line-$idx"
            }
            StyledTerminalRow(text, text)
        }
        acc.merge(screen1)
        acc.merge(screen2)
        val plain = acc.snapshot().joinToString("\n") { it.plain }
        assertEquals(1, Regex("Analyzing Feature Gaps").findAll(plain).count(), plain)
        assertTrue(plain.contains("section-b-line-20"), plain)
    }

    @Test
    fun probeLatestAntigravityTaskDerive() {
        val dir = java.io.File(System.getProperty("user.home"), ".andy/agents/task-e2acffce8a")
        val rawFile = java.io.File(dir, "scrollback.raw")
        if (!rawFile.isFile || rawFile.length() < 100_000L) return
        val content = rawFile.readText()
        val started = System.nanoTime()
        val rows = replayCaptureStyledRows(content)
        val ms = (System.nanoTime() - started) / 1_000_000
        val plain = rows.joinToString("\n") { it.plain }
        val gaps = Regex("Analyzing Feature Gaps").findAll(plain).count()
        val welcome = Regex("Welcome to the Antigravity CLI").findAll(plain).count()
        val ansi = java.io.File(dir, "scrollback.ansi").takeIf { it.isFile }?.readText().orEmpty()
        val combined = combineCommittedAndDerivedScrollback(ansi, rows.joinToString("\n") { it.ansi })
        val combinedWelcome = Regex("Welcome to the Antigravity CLI").findAll(combined).count()
        assertTrue(ms < 15_000, "derive too slow: ${ms}ms")
        assertTrue(gaps in 0..3, "Analyzing Feature Gaps duplicated: $gaps in ${rows.size} rows")
        assertTrue(welcome <= 1, "welcome frames kept: $welcome")
        assertTrue(combinedWelcome <= 1, "committed+derived duplicates boot: $combinedWelcome")
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
