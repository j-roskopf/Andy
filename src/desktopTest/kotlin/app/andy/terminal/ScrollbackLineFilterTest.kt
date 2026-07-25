package app.andy.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollbackLineFilterTest {
    @Test
    fun noiseFilterDropsSpinnerAndChromeLines() {
        assertTrue(isScrollbackNoiseLine(":: Working 24.67k tokens"))
        assertTrue(isScrollbackNoiseLine(": Working 24.68k tokens"))
        assertTrue(isScrollbackNoiseLine("  Auto · 45.1% · 1 file edited"))
        assertTrue(isScrollbackNoiseLine("  ~/Code/Andy/Andy · main"))
        assertTrue(isScrollbackNoiseLine("  → Add a follow-up"))
        assertFalse(isScrollbackNoiseLine("would it auto apply to old chats or is it just new ones?"))
        assertFalse(isScrollbackNoiseLine("So your camera-permission chat should be readable on the next open."))
    }

    @Test
    fun displayFormatterMergesDiffBlocksAndDropsToolNoise() {
        val raw = """
            Cursor Agent
            Tip: Use /plan
            if i run an action and it creates a tab
            Implementing restart-on-rerun in DesktopActionRunService
                Grepped "actionRuns.run" in .
                Edited DesktopActionRunServiceTest.kt +25
                ▎+     fun rerunningAnActionStopsThePreviousShellAndStartsFresh() =
                ▎  runBlocking {
                ▎+         val service = DesktopActionRunService(...)
                ▎ … truncated (19 more lines) · ctrl+r to review
                "app.andy.desktop.service.DesktopActionRunServiceTest" 2>&1 0ms
        """.trimIndent()

        val formatted = formatScrollbackForDisplay(raw)
        assertFalse(formatted.contains("Grepped"))
        assertFalse(formatted.contains("truncated"))
        assertTrue(formatted.contains("if i run an action and it creates a tab"))
        assertTrue(formatted.contains("Implementing restart-on-rerun"))
        assertTrue(formatted.contains("fun rerunningAnActionStopsThePreviousShellAndStartsFresh() ="))
        assertTrue(formatted.contains("runBlocking {"))
        assertFalse(formatted.contains("DesktopActionRunServiceTest\" 2>&1"))
    }

    @Test
    fun displayFormatterDropsAntigravitySlashPaletteAndRules() {
        val raw = """
                              Antigravity CLI 1.1.7
                              joseph.thomas.roskopf@gmail.com
                              Gemini 3.6 Flash (High)
                              ~/Code/Andy/Andy

            ────────────────────────────────────────────────────────────
            > hello from antigravity cli 11

              Hello! How can I help you today?

            ────────────────────────────────────────────────────────────
            > /
            > /add-dir                                            Add a directory to the workspace
              /agents                                             List available custom agents
              /artifact                                           View and review artifacts
               ↓ 130 more

              ↑/↓ Navigate · enter Select · tab Complete
            esc to cancel                                                                                    Gemini 3.6 Flash · high
        """.trimIndent()

        val formatted = formatScrollbackForDisplay(raw)
        assertTrue(formatted.contains("hello from antigravity cli 11"))
        assertTrue(formatted.contains("Hello! How can I help you today?"))
        assertFalse(formatted.contains("/add-dir"))
        assertFalse(formatted.contains("/agents"))
        assertFalse(formatted.contains("Navigate"))
        assertFalse(formatted.contains("Antigravity CLI"))
        assertFalse(formatted.contains("────"))
        assertFalse(formatted.contains("esc to cancel"))
        assertFalse(formatted.contains("@gmail.com"))
        assertFalse(formatted.contains("Gemini 3.6 Flash (High)"))
        assertFalse(formatted.contains("─── ───"))
    }

    @Test
    fun scrollbackDisplayTextFromRealCaptureIsLeftAlignedConversation() {
        val raw = """
                              Antigravity CLI 1.1.7
                              joseph.thomas.roskopf@gmail.com (Google AI Pro)
                              Gemini 3.6 Flash (High)
                              ~/Code/Andy/Andy


            ────────────────────────────────────────────────────────────
            > hello from antigravity cli 11

              Hello! How can I help you today?

            ─── ───
        """.trimIndent()
        val formatted = formatScrollbackForDisplay(raw)
        assertEquals(
            "> hello from antigravity cli 11\n\nHello! How can I help you today?",
            formatted,
        )
    }
}
