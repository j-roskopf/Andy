package app.andy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

private const val LABEL = "Temporary chat — never saved to history"
private const val DELAY_MILLIS = 1_000L

/**
 * The dwell is the whole point of this component — a tooltip that appears instantly turns every
 * pass of the cursor across the composer into a flash of popups.
 */
class HoverTooltipTest {
    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun staysHiddenUntilTheHoverDelayElapses() = runDesktopComposeUiTest(width = 240, height = 140) {
        setUpTooltip()

        hoverAnchor()
        advance(400)
        onAllNodes(hasText(LABEL)).assertCountEquals(0)

        advance(1_000)
        onAllNodes(hasText(LABEL)).assertCountEquals(1)
    }

    @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun hidesAgainWhenThePointerLeaves() = runDesktopComposeUiTest(width = 240, height = 140) {
        setUpTooltip()
        hoverAnchor()
        advance(1_400)
        onAllNodes(hasText(LABEL)).assertCountEquals(1)

        // Away from the centred anchor.
        onAllNodes(isRoot())[0].performMouseInput { moveTo(topLeft) }
        advance(100)

        onAllNodes(hasText(LABEL)).assertCountEquals(0)
    }
}

/** Virtual clock so the delay is exercised deterministically rather than slept through. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
private fun DesktopComposeUiTest.setUpTooltip() {
    mainClock.autoAdvance = false
    setContent {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HoverTooltip(LABEL, delayMillis = DELAY_MILLIS) {
                Box(Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
private fun DesktopComposeUiTest.hoverAnchor() {
    onAllNodes(isRoot())[0].performMouseInput { moveTo(center) }
}

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
private fun DesktopComposeUiTest.advance(millis: Long) {
    mainClock.advanceTimeBy(millis)
    waitForIdle()
}
