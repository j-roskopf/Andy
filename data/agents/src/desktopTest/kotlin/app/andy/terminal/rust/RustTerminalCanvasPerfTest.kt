package app.andy.terminal.rust

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.ui.theme.AndyTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalTestApi::class)
class RustTerminalCanvasPerfTest {
    @Test
    fun frameTicksDoNotRecomposeInputLayer() =
        runDesktopComposeUiTest(width = 640, height = 400) {
            val backend = FakeTerminalRenderable()
            val inputRecompositions = AtomicInteger(0)

            setContent {
                AndyTheme {
                    RustTerminalCanvas(
                        backend = backend,
                        appearance = TerminalAppearanceSnapshot(),
                        autoFocus = false,
                        modifier = Modifier.fillMaxSize(),
                        onInputLayerRecomposed = { inputRecompositions.incrementAndGet() },
                    )
                }
            }
            waitForIdle()

            val inputAfterMount = inputRecompositions.get()
            val copiesAfterMount = backend.copyCount.get()
            assertTrue(inputAfterMount >= 1, "input layer should compose at least once")
            assertTrue(copiesAfterMount >= 1, "paint surface should copy a frame on first draw")

            repeat(30) { i ->
                runOnUiThread { backend.emitTick((i + 1).toLong()) }
                waitForIdle()
            }

            val inputDelta = inputRecompositions.get() - inputAfterMount
            val copyDelta = backend.copyCount.get() - copiesAfterMount
            assertTrue(
                inputDelta <= 2,
                "frame ticks must not rebuild pointer/focus modifiers (input recompositions=$inputDelta)",
            )
            assertTrue(
                copyDelta >= 20,
                "frame ticks must still repaint via copyPaintFrame (copies=$copyDelta)",
            )
        }
}

private class FakeTerminalRenderable : RustTerminalRenderable {
    private val ticks = MutableStateFlow(0L)
    val copyCount = AtomicInteger(0)
    override val frameTick: StateFlow<Long> = ticks

    fun emitTick(value: Long) {
        ticks.value = value
    }

    override fun copyPaintFrame(into: RustTerminalFrame) {
        copyCount.incrementAndGet()
        into.columns = 40
        into.rows = 12
        val cells = into.columns * into.rows
        if (into.codePoints.size != cells) {
            into.codePoints = IntArray(cells) { ' '.code }
            into.fgArgb = IntArray(cells) { 0xFFFFFFFF.toInt() }
            into.bgArgb = IntArray(cells) { 0xFF000000.toInt() }
            into.attrs = ByteArray(cells)
        }
    }

    override fun resize(cols: Int, rows: Int) = Unit
    override fun scrollDisplay(delta: Int) {
        ticks.value = ticks.value + 1
    }
    override fun updateAppearance(appearance: TerminalAppearanceSnapshot) = Unit
    override fun mouseFlags(): Int = 0
    override fun bracketedPasteEnabled(): Boolean = false
    override fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
}
