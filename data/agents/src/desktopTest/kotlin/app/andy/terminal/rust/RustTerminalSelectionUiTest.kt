package app.andy.terminal.rust

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.ui.theme.AndyTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RustTerminalSelectionUiTest {

    @Test
    fun dragSelectsTextNativelyEvenWhenMouseReportingIsActive() =
        runDesktopComposeUiTest(width = 640, height = 300) {
            val backend = SelectionTestRenderable(
                lines = listOf("hello world from tmux agent"),
                mouseFlagsValue = RustMouseFlags.REPORTING or RustMouseFlags.SGR or RustMouseFlags.DRAG,
            )
            val clipboard = RecordingClipboard()

            setContent {
                AndyTheme {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        RustTerminalCanvas(
                            backend = backend,
                            appearance = TerminalAppearanceSnapshot(),
                            autoFocus = true,
                            modifier = Modifier.fillMaxSize().testTag("terminal"),
                        )
                    }
                }
            }
            waitForIdle()

            // Drag across "hello world" on row 0.
            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(2f, 8f))
                press()
                moveTo(Offset(95f, 8f))
                release()
            }
            waitForIdle()

            // Mouse drag must NOT be forwarded to tmux (which would copy-pipe-and-cancel).
            assertTrue(
                backend.writes.isEmpty(),
                "drag selection should not forward mouse events to tmux, got=${backend.writes}",
            )
            assertTrue(
                clipboard.copied.isNotEmpty() && clipboard.copied.last().contains("hello"),
                "drag release should copy selected text, got=${clipboard.copied}",
            )

            // Clear clipboard and press Cmd+C: selection highlight must still be active after release.
            val copiedOnRelease = clipboard.copied.last()
            clipboard.copied.clear()
            onNodeWithTag("terminal").performKeyInput {
                withKeyDown(Key.MetaLeft) {
                    pressKey(Key.C)
                }
            }
            waitForIdle()

            assertEquals(
                listOf(copiedOnRelease),
                clipboard.copied,
                "Cmd+C after release should copy the still-highlighted selection",
            )
            assertTrue(
                backend.writes.isEmpty(),
                "Cmd+C should not send bytes to PTY, got=${backend.writes}",
            )

            // Pressing Escape clears the selection without forwarding ESC to the PTY.
            clipboard.copied.clear()
            onNodeWithTag("terminal").performKeyInput {
                pressKey(Key.Escape)
                withKeyDown(Key.MetaLeft) {
                    pressKey(Key.C)
                }
            }
            waitForIdle()

            assertTrue(clipboard.copied.isEmpty(), "Escape should clear the selection")
            assertTrue(backend.writes.isEmpty(), "Escape clearing selection must not write ESC to PTY")
        }

    @Test
    fun singleClickStillForwardsSgrPressAndReleaseWhenMouseReportingIsActive() =
        runDesktopComposeUiTest(width = 640, height = 300) {
            val backend = SelectionTestRenderable(
                lines = listOf("clickable button"),
                mouseFlagsValue = RustMouseFlags.REPORTING or RustMouseFlags.SGR,
            )
            val clipboard = RecordingClipboard()

            setContent {
                AndyTheme {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        RustTerminalCanvas(
                            backend = backend,
                            appearance = TerminalAppearanceSnapshot(),
                            autoFocus = true,
                            modifier = Modifier.fillMaxSize().testTag("terminal"),
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(20f, 8f))
                press()
                release()
            }
            waitForIdle()

            assertEquals(1, backend.writes.size, "press + release should be combined in one ordered write")
            val payload = backend.writes.single()
            assertTrue(
                payload.contains("\u001B[<0;") && payload.contains("M\u001B[<0;") && payload.endsWith("m"),
                "expected SGR press+release in order, got=$payload",
            )
            assertTrue(clipboard.copied.isEmpty(), "single click should not copy to clipboard")
        }

    @Test
    fun doubleClickAndTripleClickSelectWordAndLineWithoutForwardingToTmux() =
        runDesktopComposeUiTest(width = 640, height = 300) {
            val backend = SelectionTestRenderable(
                lines = listOf("alpha beta_gamma delta"),
                mouseFlagsValue = RustMouseFlags.REPORTING or RustMouseFlags.SGR or RustMouseFlags.DRAG,
            )
            val clipboard = RecordingClipboard()

            setContent {
                AndyTheme {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        RustTerminalCanvas(
                            backend = backend,
                            appearance = TerminalAppearanceSnapshot(),
                            autoFocus = true,
                            modifier = Modifier.fillMaxSize().testTag("terminal"),
                        )
                    }
                }
            }
            waitForIdle()

            // Click twice on "alpha" (col 1, row 0).
            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(10f, 8f))
                press()
                release()
                advanceEventTime(50L)
                press()
                release()
            }
            waitForIdle()

            // Only the first click was forwarded; the second click was intercepted for word selection.
            assertEquals(1, backend.writes.size, "double-click must not forward second click to tmux")
            assertEquals("alpha", clipboard.copied.lastOrNull())

            // Triple-click within timeout selects the full line without forwarding.
            onNodeWithTag("terminal").performMouseInput {
                advanceEventTime(50L)
                press()
                release()
            }
            waitForIdle()

            assertEquals(1, backend.writes.size, "triple-click must not forward third click to tmux")
            assertEquals("alpha beta_gamma delta", clipboard.copied.lastOrNull())
        }

    @Test
    fun localScrollClearsFinishedSelection() =
        runDesktopComposeUiTest(width = 640, height = 300) {
            val backend = SelectionTestRenderable(
                lines = listOf("scroll clears selection"),
                mouseFlagsValue = RustMouseFlags.REPORTING or RustMouseFlags.SGR,
            )
            val clipboard = RecordingClipboard()

            setContent {
                AndyTheme {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        RustTerminalCanvas(
                            backend = backend,
                            appearance = TerminalAppearanceSnapshot(),
                            autoFocus = true,
                            modifier = Modifier.fillMaxSize().testTag("terminal"),
                        )
                    }
                }
            }
            waitForIdle()

            // Shift+drag selects locally even though mouse reporting is active.
            onNodeWithTag("terminal").performKeyInput { keyDown(Key.ShiftLeft) }
            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(2f, 8f))
                press()
                moveTo(Offset(140f, 8f))
                release()
            }
            onNodeWithTag("terminal").performKeyInput { keyUp(Key.ShiftLeft) }
            waitForIdle()
            assertTrue(
                clipboard.copied.isNotEmpty(),
                "shift drag should copy the selection, got=${clipboard.copied}",
            )

            // Shift+wheel scrolls locally (no PTY write); the finished selection must be dropped.
            clipboard.copied.clear()
            onNodeWithTag("terminal").performKeyInput { keyDown(Key.ShiftLeft) }
            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(20f, 60f))
                scroll(-1f)
            }
            onNodeWithTag("terminal").performKeyInput { keyUp(Key.ShiftLeft) }
            waitForIdle()

            onNodeWithTag("terminal").performKeyInput {
                withKeyDown(Key.MetaLeft) { pressKey(Key.C) }
            }
            waitForIdle()

            assertTrue(
                clipboard.copied.isEmpty(),
                "local scroll should clear the finished selection, got=${clipboard.copied}",
            )
        }

    @Test
    fun releasingShiftMidDragStillCompletesLocalSelection() =
        runDesktopComposeUiTest(width = 640, height = 300) {
            val backend = SelectionTestRenderable(
                lines = listOf("shift drag test line"),
                mouseFlagsValue = RustMouseFlags.REPORTING or RustMouseFlags.SGR or RustMouseFlags.DRAG,
            )
            val clipboard = RecordingClipboard()

            setContent {
                AndyTheme {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        RustTerminalCanvas(
                            backend = backend,
                            appearance = TerminalAppearanceSnapshot(),
                            autoFocus = true,
                            modifier = Modifier.fillMaxSize().testTag("terminal"),
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithTag("terminal").performKeyInput { keyDown(Key.ShiftLeft) }
            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(2f, 8f))
                press()
                moveTo(Offset(40f, 8f))
            }
            onNodeWithTag("terminal").performKeyInput { keyUp(Key.ShiftLeft) }
            onNodeWithTag("terminal").performMouseInput {
                moveTo(Offset(90f, 8f))
                release()
            }
            waitForIdle()

            assertTrue(
                backend.writes.isEmpty(),
                "releasing Shift mid-drag must not send a stray mouse release, got=${backend.writes}",
            )
            assertTrue(
                clipboard.copied.isNotEmpty() && clipboard.copied.last().contains("shift"),
                "selection should finish and copy after Shift is released mid-drag, got=${clipboard.copied}",
            )
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private class RecordingClipboard : Clipboard {
    val copied = mutableListOf<String>()
    private var lastEntry: ClipEntry? = null

    override suspend fun getClipEntry(): ClipEntry? = lastEntry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        lastEntry = clipEntry
        val transferable = clipEntry?.nativeClipEntry as? Transferable ?: return
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String
            if (text != null) copied += text
        }
    }

    override val nativeClipboard: NativeClipboard
        get() = throw UnsupportedOperationException("not needed in test")
}

private class SelectionTestRenderable(
    private val lines: List<String>,
    private val mouseFlagsValue: Int,
) : RustTerminalRenderable {
    private val ticks = MutableStateFlow(1L)
    val writes = mutableListOf<String>()

    override val frameTick: StateFlow<Long> = ticks

    override fun copyPaintFrame(into: RustTerminalFrame) {
        val cols = 60
        val rows = 12
        into.columns = cols
        into.rows = rows
        into.displayOffset = 0
        into.historySize = 0
        val cells = cols * rows
        if (into.codePoints.size != cells) {
            into.codePoints = IntArray(cells) { ' '.code }
            into.fgArgb = IntArray(cells) { 0xFFFFFFFF.toInt() }
            into.bgArgb = IntArray(cells) { 0xFF000000.toInt() }
            into.attrs = ByteArray(cells)
        } else {
            into.codePoints.fill(' '.code)
        }
        lines.forEachIndexed { r, line ->
            if (r < rows) {
                line.forEachIndexed { c, ch ->
                    if (c < cols) into.codePoints[r * cols + c] = ch.code
                }
            }
        }
    }

    override fun resize(cols: Int, rows: Int) = Unit
    override fun scrollDisplay(delta: Int) = Unit
    override fun updateAppearance(appearance: TerminalAppearanceSnapshot) = Unit
    override fun mouseFlags(): Int = mouseFlagsValue
    override fun bracketedPasteEnabled(): Boolean = false
    override fun write(bytes: ByteArray) {
        writes += bytes.decodeToString()
    }
    override fun close() = Unit
}
