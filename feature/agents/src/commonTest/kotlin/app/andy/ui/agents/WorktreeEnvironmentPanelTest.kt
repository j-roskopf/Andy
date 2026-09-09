package app.andy.ui.agents

import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreeEnvironmentPanelTest {

    @Test
    fun unmeasuredPaneDoesNotTuck() {
        assertFalse(
            environmentCardWouldEclipseContent(
                paneWidth = 0.dp,
                contentFullBleed = false,
            ),
        )
    }

    @Test
    fun fullBleedPaneAlwaysTucks() {
        assertTrue(
            environmentCardWouldEclipseContent(
                paneWidth = 2_000.dp,
                contentFullBleed = true,
            ),
        )
    }

    @Test
    fun halfMonitorCenteredChatTucks() {
        // Typical half-monitor chat pane: content fills to ChatContentMaxWidth and the
        // floating card sits on top of the message column's right edge.
        assertTrue(
            environmentCardWouldEclipseContent(
                paneWidth = 900.dp,
                contentFullBleed = false,
            ),
        )
        assertTrue(
            environmentCardWouldEclipseContent(
                paneWidth = AndyLayout.ChatContentMaxWidth,
                contentFullBleed = false,
            ),
        )
    }

    @Test
    fun widePaneWithSideGuttersKeepsCardExpanded() {
        // Need room for the 800dp column, card, end padding, clearance, and end inset.
        assertFalse(
            environmentCardWouldEclipseContent(
                paneWidth = 1_400.dp,
                contentFullBleed = false,
            ),
        )
    }

    @Test
    fun justWideEnoughKeepsCardExpanded() {
        // contentRight = (pane + 800) / 2 - 26
        // panelLeft = pane - 8 - 248
        // clear when panelLeft >= contentRight + 16
        // ⇒ pane - 256 >= (pane + 800) / 2 - 10
        // ⇒ pane >= ~1,292dp
        assertFalse(
            environmentCardWouldEclipseContent(
                paneWidth = 1_300.dp,
                contentFullBleed = false,
            ),
        )
        assertTrue(
            environmentCardWouldEclipseContent(
                paneWidth = 1_200.dp,
                contentFullBleed = false,
            ),
        )
    }
}
