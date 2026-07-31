package app.andy.terminal

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.emulator.BossEmulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalHistoryControllerTest {
    @Test
    fun recordsRowsWhenTheLiveBufferScrolls() {
        val styles = StyleState()
        val buffer = TerminalTextBuffer(20, 2, styles, 32)
        val terminal = BossTerminal(HeadlessTerminalDisplay(20, 2), buffer, styles)
        TerminalHistoryController(buffer, maxRows = 32).use { history ->
            feed(terminal, "one\r\ntwo\r\nthree\r\n")
            history.refresh()

            val rows = history.snapshot().map { it.plain.trim() }.filter { it.isNotEmpty() }
            assertTrue(rows.contains("one"), "history=$rows")
            assertTrue(rows.contains("two"), "history=$rows")
        }
        terminal.disconnected()
    }

    @Test
    fun doesNotDuplicateExistingRowsWhenAnotherRowScrolls() {
        val styles = StyleState()
        val buffer = TerminalTextBuffer(20, 2, styles, 32)
        val terminal = BossTerminal(HeadlessTerminalDisplay(20, 2), buffer, styles)
        TerminalHistoryController(buffer, maxRows = 32).use { history ->
            feed(terminal, "one\r\ntwo\r\nthree\r\n")
            feed(terminal, "four\r\n")
            history.refresh()

            val rows = history.snapshot().map { it.plain.trim() }.filter { it.isNotEmpty() }
            assertEquals(1, rows.count { it == "one" })
            assertEquals(1, rows.count { it == "two" })
        }
        terminal.disconnected()
    }

    private fun feed(terminal: BossTerminal, text: String) {
        val emulator = BossEmulator(
            ArrayTerminalDataStream(text.toCharArray()),
            terminal,
            allowKittyFileTransfers = false,
        )
        while (emulator.hasNext()) emulator.next()
    }
}
