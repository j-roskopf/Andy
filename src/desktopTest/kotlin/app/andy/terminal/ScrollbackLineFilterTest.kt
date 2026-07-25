package app.andy.terminal

import kotlin.test.Test
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
}
