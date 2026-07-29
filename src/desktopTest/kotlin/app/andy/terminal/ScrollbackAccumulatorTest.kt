package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollbackAccumulatorTest {
    private fun screen(vararg lines: String): List<StyledTerminalRow> =
        lines.map { StyledTerminalRow(plain = stripAnsi(it), ansi = it) }

    @Test
    fun firstSnapshotIsKeptVerbatim() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("\u001b[36m> hello\u001b[0m", "  world"))
        assertEquals("\u001b[36m> hello\u001b[0m\n  world", accumulator.render())
    }

    @Test
    fun unchangedScreenDoesNotDuplicate() {
        val accumulator = ScrollbackAccumulator()
        repeat(4) { accumulator.merge(screen("> hello", "  world")) }
        assertEquals("> hello\n  world", accumulator.render())
    }

    @Test
    fun redrawnStatusLineReplacesItselfInsteadOfPilingUp() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("> build the thing", "  working on it", "⠋ Working 1.2k tokens"))
        accumulator.merge(screen("> build the thing", "  working on it", "⠙ Working 2.4k tokens"))
        accumulator.merge(screen("> build the thing", "  working on it", "⠹ Working 3.6k tokens"))
        assertEquals(
            "> build the thing\n  working on it\n⠹ Working 3.6k tokens",
            accumulator.render(),
        )
    }

    @Test
    fun cursorStatusFooterRedrawReplacesItselfInsteadOfPilingUp() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(
            screen(
                "> fix flicker",
                "  Cursor Grok 4.5 High Fast · 54.6% · 5 files edited",
                "~/Code/Andy/Andy · jr/cli · #57",
            ),
        )
        accumulator.merge(
            screen(
                "> fix flicker",
                "  Cursor Grok 4.5 High Fast · 55.1% · 6 files edited",
                "~/Code/Andy/Andy · jr/cli · #57",
            ),
        )
        assertEquals(
            "> fix flicker\n  Cursor Grok 4.5 High Fast · 55.1% · 6 files edited\n~/Code/Andy/Andy · jr/cli · #57",
            accumulator.render(),
        )
    }

    @Test
    fun codexStartupRedrawsReplaceBootChromeAndPromptInsteadOfDuplicatingThem() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(
            screen(
                "╭───────────────────────────────────────╮",
                "│ >_ OpenAI Codex (v0.146.0-alpha.3.1)  │",
                "│                                       │",
                "│ model:     loading   /model to change │",
                "│ directory: ~/Code/Andy/Andy           │",
                "╰───────────────────────────────────────╯",
            ),
        )
        accumulator.merge(
            screen(
                "╭─────────────────────────────────────────────────╮",
                "│ >_ OpenAI Codex (v0.146.0-alpha.3.1)            │",
                "│                                                 │",
                "│ model:     gpt-5.6-luna high   /model to change │",
                "│ directory: ~/Code/Andy/Andy                     │",
                "╰─────────────────────────────────────────────────╯",
                "",
                "Tip: Try the Desktop app.",
                "",
                "› create a very long 20 step plan",
                "",
                "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan.",
                "",
                "• Starting MCP servers (2/4)",
            ),
        )
        accumulator.merge(
            screen(
                "│ >_ OpenAI Codex (v0.146.0-alpha.3.1)            │",
                "│                                                 │",
                "│ model:     gpt-5.6-luna high   /model to change │",
                "│ directory: ~/Code/Andy/Andy                     │",
                "╰─────────────────────────────────────────────────╯",
                "",
                "Tip: Try the Desktop app.",
                "",
                "› create a very long 20 step plan",
                "",
                "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan.",
                "",
                "• Working (6s)",
            ),
        )

        val rendered = accumulator.render()
        assertEquals(1, Regex("OpenAI Codex").findAll(rendered).count(), rendered)
        assertEquals(1, Regex("create a very long 20 step plan").findAll(rendered).count(), rendered)
        assertEquals(1, Regex("Plan mode is active").findAll(rendered).count(), rendered)
    }

    @Test
    fun codexStartupRedrawsTolerateInsertedRowsAndChangingWrapsFromRealCapture() {
        val boot = listOf(
            "╭─────────────────────────────────────────────────╮",
            "│ >_ OpenAI Codex (v0.146.0-alpha.3.1)            │",
            "│                                                 │",
            "│ model:     gpt-5.6-luna high   /model to change │",
            "│ directory: ~/Code/Andy/Andy                     │",
            "╰─────────────────────────────────────────────────╯",
            "",
            "  Tip: New Use /fast to enable our fastest inference with increased plan usage.",
            "",
            "› create a very long 20 step plan on how to be a better software engineer",
            "",
        )
        val warning = listOf(
            "⚠ GitHub MCP does not support OAuth. Log in by adding a personal access token",
            "  (https://github.com/settings/personal-access-tokens) to your environment and",
            "  config.toml:",
            "  [mcp_servers.github]",
            "  bearer_token_env_var = CODEX_GITHUB_PERSONAL_ACCESS_TOKEN",
        )
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(
            screen(
                *(boot + listOf(
                    "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan.",
                    "  Do not edit files, apply patches, or run commands that modify the workspace.",
                    "",
                ) + warning).toTypedArray(),
            ),
        )
        accumulator.merge(
            screen(
                *(listOf(
                    boot[0],
                    boot[1],
                    // Captured from the reported task: a partial banner repaint inserted
                    // one identical row and shifted every later row down by one.
                    boot[1],
                ) + boot.drop(2) + listOf(
                    "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan. Do not edit files, appl",
                    "",
                    "⚠ GitHub MCP does not support OAuth. Log in by adding a personal access token (https://github.com/settings/personal-acce",
                    "ss-tokens) to your environment and",
                    "  config.toml:",
                    "  [mcp_servers.github]",
                    "  bearer_token_env_var = CODEX_GITHUB_PERSONAL_ACCESS_TOKEN",
                    "",
                    "⚠ MCP startup incomplete (failed: github)",
                )).toTypedArray(),
            ),
        )
        accumulator.merge(
            screen(
                *(boot + listOf(
                    "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan. Do not edit files, appl",
                    "",
                    "⚠ GitHub MCP does not support OAuth. Log in by adding a personal access token (https://github.com/settings/personal-acce",
                    "  config.toml:",
                    "  [mcp_servers.github]",
                    "  bearer_token_env_var = CODEX_GITHUB_PERSONAL_ACCESS_TOKEN",
                    "",
                    "⚠ MCP startup incomplete (failed: github)",
                    "",
                    "• # 20-Step Plan to Become a Better Software Engineer",
                )).toTypedArray(),
            ),
        )

        val rendered = accumulator.render()
        assertEquals(1, Regex("OpenAI Codex").findAll(rendered).count(), rendered)
        assertEquals(
            1,
            Regex("create a very long 20 step plan on how to be a better software engineer")
                .findAll(rendered)
                .count(),
            rendered,
        )
        assertEquals(1, Regex("Plan mode is active").findAll(rendered).count(), rendered)
        assertEquals(1, Regex("MCP startup incomplete").findAll(rendered).count(), rendered)
    }

    @Test
    fun oneReconstructedSnapshotCompactsRepeatedCodexStartupFramesFromRealCapture() {
        val boot = listOf(
            "╭─────────────────────────────────────────────────╮",
            "│ >_ OpenAI Codex (v0.146.0-alpha.3.1)            │",
            "│                                                 │",
            "│ model:     gpt-5.6-luna high   /model to change │",
            "│ directory: ~/Code/Andy/Andy                     │",
            "╰─────────────────────────────────────────────────╯",
            "",
            "  Tip: New Use /fast to enable our fastest inference with increased plan usage.",
            "",
            "› create a very long 20 step plan on how to be a better software engineer",
            "",
        )
        val firstFrame = boot + listOf(
            "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan.",
            "  Do not edit files, apply patches, or run commands that modify the workspace.",
            "",
            "⚠ GitHub MCP does not support OAuth. Log in by adding a personal access token",
            "  (https://github.com/settings/personal-access-tokens) to your environment and",
            "  config.toml:",
            "  [mcp_servers.github]",
            "  bearer_token_env_var = CODEX_GITHUB_PERSONAL_ACCESS_TOKEN",
        )
        val middleFrame = listOf(boot[0], boot[1], boot[1]) + boot.drop(2) + listOf(
            "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan. Do not edit files, appl",
            "",
            "⚠ GitHub MCP does not support OAuth. Log in by adding a personal access token (https://github.com/settings/personal-acce",
            "ss-tokens) to your environment and",
            "  config.toml:",
            "  [mcp_servers.github]",
            "  bearer_token_env_var = CODEX_GITHUB_PERSONAL_ACCESS_TOKEN",
            "",
            "⚠ MCP startup incomplete (failed: github)",
        )
        val finalFrame = boot + listOf(
            "  Plan mode is active. Inspect and analyze the task, then return a concrete implementation plan. Do not edit files, appl",
            "",
            "⚠ GitHub MCP does not support OAuth. Log in by adding a personal access token (https://github.com/settings/personal-acce",
            "  config.toml:",
            "  [mcp_servers.github]",
            "  bearer_token_env_var = CODEX_GITHUB_PERSONAL_ACCESS_TOKEN",
            "",
            "⚠ MCP startup incomplete (failed: github)",
            "",
            "• # 20-Step Plan to Become a Better Software Engineer",
        )
        val accumulator = ScrollbackAccumulator()

        accumulator.merge(screen(*(firstFrame + middleFrame + finalFrame).toTypedArray()))

        val rendered = accumulator.render()
        assertEquals(1, Regex("OpenAI Codex").findAll(rendered).count(), rendered)
        assertEquals(
            1,
            Regex("create a very long 20 step plan on how to be a better software engineer")
                .findAll(rendered)
                .count(),
            rendered,
        )
        assertEquals(1, Regex("Plan mode is active").findAll(rendered).count(), rendered)
        assertEquals(1, Regex("MCP startup incomplete").findAll(rendered).count(), rendered)
        assertTrue(rendered.contains("# 20-Step Plan to Become a Better Software Engineer"), rendered)
    }

    @Test
    fun providerStartupCompactionKeepsOneHeaderPerRealSession() {
        val boot = """
            ╭─────────────────────────────────────────────────╮
            │ >_ OpenAI Codex (v0.146.0-alpha.3.1)            │
            │ model:     gpt-5.6-luna high   /model to change │
            │ directory: ~/Code/Andy/Andy                     │
            ╰─────────────────────────────────────────────────╯
        """.trimIndent()
        val twoSessions = "$boot\n› first prompt$SCROLLBACK_SESSION_SEPARATOR$boot\n› resumed prompt"

        val compacted = compactRepeatedProviderStartupText(twoSessions)

        assertEquals(2, Regex("OpenAI Codex").findAll(compacted).count(), compacted)
        assertTrue(compacted.contains("› first prompt"), compacted)
        assertTrue(compacted.contains("› resumed prompt"), compacted)
        assertTrue(compacted.contains(SCROLLBACK_SESSION_SEPARATOR.trim()), compacted)
    }

    @Test
    fun scrolledRowsAreFrozenIntoHistory() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("line 1", "line 2", "line 3", "line 4"))
        accumulator.merge(screen("line 3", "line 4", "line 5", "line 6"))
        accumulator.merge(screen("line 5", "line 6", "line 7", "line 8"))
        assertEquals(
            (1..8).joinToString("\n") { "line $it" },
            accumulator.render(),
        )
    }

    @Test
    fun fixedFooterDoesNotBlockScrollAlignment() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("reply a", "reply b", "reply c", "─────", "> type here"))
        accumulator.merge(screen("reply b", "reply c", "reply d", "─────", "> type here"))
        assertEquals(
            "reply a\nreply b\nreply c\nreply d\n─────\n> type here",
            accumulator.render(),
        )
    }

    @Test
    fun unrelatedScreenIsAppendedWholesale() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("old session output"))
        accumulator.merge(screen("brand new screen", "nothing in common"))
        assertEquals(
            "old session output\nbrand new screen\nnothing in common",
            accumulator.render(),
        )
    }

    @Test
    fun growingBottomLineIsRewrittenNotAppendedTwice() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("> question", "Ans"))
        accumulator.merge(screen("> question", "Answer so"))
        accumulator.merge(screen("> question", "Answer so far", "and more"))
        assertEquals("> question\nAnswer so far\nand more", accumulator.render())
    }

    @Test
    fun trailingBlankRowsOfTheScreenAreIgnored() {
        val accumulator = ScrollbackAccumulator()
        accumulator.merge(screen("only line", "", "", ""))
        assertEquals("only line", accumulator.render())
    }

    @Test
    fun stylingSurvivesRepeatedMerges() {
        val accumulator = ScrollbackAccumulator()
        val styled = "\u001b[38;2;255;128;0m▎\u001b[0m\u001b[1m diff added\u001b[0m"
        accumulator.merge(screen("> patch it", styled))
        accumulator.merge(screen("> patch it", styled, "done"))
        assertTrue(accumulator.render().contains("\u001b[38;2;255;128;0m"))
        assertEquals("> patch it\n$styled\ndone", accumulator.render())
    }

    @Test
    fun styledRowsFromAnsiTextKeepsEscapesAndStripsThemForPlain() {
        val rows = styledRowsFromAnsiText("\u001b[31mred\u001b[0m\nplain\n")
        assertEquals(2, rows.size)
        assertEquals("red", rows[0].plain)
        assertEquals("\u001b[31mred\u001b[0m", rows[0].ansi)
        assertEquals("plain", rows[1].plain)
    }

    @Test
    fun seededHistoryIsNotWrittenTwiceOnReattach() {
        // Reattaching re-captures a pane whose output is already on disk.
        val saved = screen("reply a", "reply b", "reply c")
        val accumulator = ScrollbackAccumulator()
        accumulator.seed(saved)
        accumulator.merge(screen("reply b", "reply c", "reply d"))
        assertEquals("reply a\nreply b\nreply c\nreply d", accumulator.render())
    }

    @Test
    fun overlapPrefersLongestAlignmentOnTies() {
        val captured = screen("a", "b", "a", "b")
        val incoming = screen("a", "b")
        assertEquals(2, scrollbackSnapshotOverlap(captured, incoming))
    }

    @Test
    fun prunedOverlapScanAgreesWithExhaustiveScoring() {
        // scrollbackSnapshotOverlap prunes alignments it can prove cannot win, which took the
        // scan off the O(n²) path that dominated CPU. The pruning must not change *which*
        // alignment wins, so score every candidate exhaustively and compare.
        val vocabulary = listOf(
            "",
            "   ",
            "> ask the model something",
            "⠙ Working 2.4k tokens",
            "────────────────────────────────",
            "│  │",
            "ordinary prose that carries real content",
            "a fairly long response line that another snapshot may truncate part way through",
            "a fairly long response line that another snapshot may trun",
            "repeated bullet",
            "exit 0",
        )
        val random = kotlin.random.Random(seed = 20260729)
        repeat(400) { case ->
            val captured = List(random.nextInt(0, 14)) { vocabulary.random(random) }
            val incoming = List(random.nextInt(0, 14)) { vocabulary.random(random) }
            val capturedRows = screen(*captured.toTypedArray())
            val incomingRows = screen(*incoming.toTypedArray())
            assertEquals(
                referenceOverlap(capturedRows, incomingRows),
                scrollbackSnapshotOverlap(capturedRows, incomingRows),
                "case $case disagreed\ncaptured=$captured\nincoming=$incoming",
            )
        }
    }

    /** The scan as it read before pruning: score every alignment, longest wins ties. */
    private fun referenceOverlap(
        captured: List<StyledTerminalRow>,
        snapshot: List<StyledTerminalRow>,
    ): Int {
        val longest = minOf(captured.size, snapshot.size)
        if (longest == 0) return 0
        var bestOverlap = 0
        var bestScore = 0
        for (overlap in longest downTo 1) {
            val base = captured.size - overlap
            var score = 0
            for (offset in 0 until overlap) {
                val previous = captured[base + offset].plain
                val current = snapshot[offset].plain
                when {
                    referenceEquivalent(previous, current) -> score += 2
                    current.isNotBlank() -> score -= 1
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestOverlap = overlap
            }
        }
        return bestOverlap
    }

    private fun referenceEquivalent(previous: String, current: String): Boolean {
        if (previous == current) return current.isNotBlank()
        if (isVolatileTerminalChromeLine(previous) && isVolatileTerminalChromeLine(current)) {
            return true
        }
        val left = previous.trim()
        val right = current.trim()
        val shorter = minOf(left.length, right.length)
        return shorter >= 32 && (left.startsWith(right) || right.startsWith(left))
    }
}
