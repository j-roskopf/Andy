package app.andy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.andy.ui.theme.AndyTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ThinkingOrbComposePerfTest {
    @Test
    fun staticOrbDoesNotRestartCompositionWhileIdle() =
        runDesktopComposeUiTest(width = 80, height = 80) {
            val compositions = AtomicInteger(0)
            setContent {
                AndyTheme {
                    ThinkingOrb(
                        animate = false,
                        onComposed = { compositions.incrementAndGet() },
                    )
                }
            }
            waitForIdle()
            val afterMount = compositions.get()
            assertTrue(afterMount >= 1)

            // Non-animating orbs never subscribe to ThinkingOrbClock, so this must
            // stay static regardless of how long the shared ticker runs elsewhere.
            runBlocking { delay(500) }
            waitForIdle()

            val delta = compositions.get() - afterMount
            assertTrue(
                delta <= 2,
                "Non-animating ThinkingOrb must stay static (restarts=$delta).",
            )
        }

    @Test
    fun animatingOrbTicksAtABoundedLowFrequency() =
        runDesktopComposeUiTest(width = 80, height = 80) {
            val compositions = AtomicInteger(0)
            setContent {
                AndyTheme {
                    ThinkingOrb(
                        animate = true,
                        onComposed = { compositions.incrementAndGet() },
                    )
                }
            }
            waitForIdle()
            val afterMount = compositions.get()

            runBlocking { delay(600) }
            waitForIdle()

            val delta = compositions.get() - afterMount
            // ThinkingOrbClock ticks every ~100ms, so 600ms should yield a handful of
            // restarts — proof the orb is moving again — without turning into a tight
            // per-frame loop.
            assertTrue(
                delta in 1..15,
                "Animating ThinkingOrb ticked $delta times in 600ms; expected a bounded " +
                    "low-frequency cadence (frozen or runaway both indicate a regression).",
            )
        }

    @Test
    fun concurrentOrbsShareOneClockTickInsteadOfDriftingIndependently() =
        runDesktopComposeUiTest(width = 200, height = 80) {
            val counters = List(4) { AtomicInteger(0) }
            setContent {
                AndyTheme {
                    Column {
                        counters.forEach { counter ->
                            ThinkingOrb(animate = true, onComposed = { counter.incrementAndGet() })
                        }
                    }
                }
            }
            waitForIdle()
            val baseline = counters.map { it.get() }

            runBlocking { delay(600) }
            waitForIdle()

            val deltas = counters.mapIndexed { i, c -> c.get() - baseline[i] }
            assertTrue(deltas.all { it > 0 }, "Every orb should have ticked at least once: $deltas")
            // All four orbs read the same ThinkingOrbClock state, so a tick invalidates
            // and redraws them together in one recomposition pass rather than each
            // running its own independently-timed loop.
            assertTrue(
                (deltas.max() - deltas.min()) <= 1,
                "Orbs drifted out of lockstep, indicating independent timers: $deltas",
            )
        }
}
