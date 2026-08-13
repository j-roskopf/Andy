package app.andy.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replica of BossTerm's redraw gate, transcribed from the 1.2.143 bytecode of
 * `ComposeTerminalDisplay.requestRedraw` / `setSynchronizedUpdate`:
 *
 *  - `requestRedraw()` while gated  -> sets `pendingRedrawDuringSync`, emits nothing.
 *  - `setSynchronizedUpdate(false)` -> emits exactly one redraw iff something was pending.
 *
 * [redraws] therefore counts what `TerminalCanvasRenderer.renderTerminal` would actually be
 * asked to do, which is the quantity the limiter exists to bound.
 */
private class FakeRedrawGate : SynchronizedUpdateGate {
    private val lock = Any()
    private var gated = false
    private var pending = false

    val redraws = AtomicInteger(0)
    val requests = AtomicInteger(0)

    /**
     * Compose coalesces every invalidation landing within one frame into a single
     * recomposition, so `renderTerminal` is paid once per *frame that was invalidated at all*,
     * not once per invalidation. Bucketing invalidations by frame period models that, and is
     * the quantity the limiter actually exists to reduce.
     */
    private val invalidatedFrames = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    val renderFrames: Int get() = invalidatedFrames.size

    /** True while output is being withheld from the renderer. */
    val isGated: Boolean get() = synchronized(lock) { gated }

    private fun invalidate() {
        redraws.incrementAndGet()
        invalidatedFrames.add(System.nanoTime() / FRAME_NANOS)
    }

    fun requestRedraw() {
        requests.incrementAndGet()
        synchronized(lock) {
            if (gated) {
                pending = true
                return
            }
        }
        invalidate()
    }

    override fun setSynchronizedUpdate(enabled: Boolean) {
        val shouldRedraw: Boolean
        synchronized(lock) {
            if (enabled) {
                if (!gated) {
                    gated = true
                    pending = false
                }
                shouldRedraw = false
            } else {
                shouldRedraw = gated && pending
                if (gated) {
                    gated = false
                    pending = false
                }
            }
        }
        if (shouldRedraw) invalidate()
    }

    private companion object {
        /** One 60Hz frame. */
        const val FRAME_NANOS = 16_700_000L
    }
}

class TerminalFrameLimiterTest {

    @Test
    fun `default fps stays at the measured 10-20 percent CPU ceiling`() {
        // 15fps + throughput + no hyperlinks measured 20.2% process CPU; 24fps+both was 26%.
        // Raising DEFAULT_FPS without a cheaper renderer exits the budget band — see
        // docs/terminal-performance-investigation.md.
        assertEquals(15L, TerminalFrameLimiter.DEFAULT_FPS)
        val previous = System.getProperty("andy.terminal.repaint.fps")
        try {
            System.clearProperty("andy.terminal.repaint.fps")
            val limiter = TerminalFrameLimiter(gate = FakeRedrawGate())
            assertTrue(limiter.isEnabled, "default 15fps cap must be enabled")
            limiter.close()
        } finally {
            if (previous == null) System.clearProperty("andy.terminal.repaint.fps")
            else System.setProperty("andy.terminal.repaint.fps", previous)
        }
    }

    private fun limiter(
        gate: SynchronizedUpdateGate,
        intervalMs: Long = 25L,
        foreground: () -> Boolean = { true },
        churning: () -> Boolean = { true },
        renderWindowMs: Long = 5L,
    ) = TerminalFrameLimiter(
        gate = gate,
        foregroundProvider = foreground,
        churningProvider = churning,
        foregroundIntervalMillis = intervalMs,
        backgroundIntervalMillis = intervalMs * 10,
        renderWindowMillis = renderWindowMs,
    )

    /** Drive [TerminalFrameLimiter.gateLoop] directly — no Compose Main dispatcher needed. */
    private fun CoroutineScope.driveLoop(limiter: TerminalFrameLimiter): Job =
        launch(Dispatchers.Default) { limiter.gateLoop() }

    /** Mimic the emulator: BossTerm requests a redraw per emulated character (~2000/sec live). */
    private fun CoroutineScope.driveCharacterStorm(gate: FakeRedrawGate): Job =
        launch(Dispatchers.Default) {
            while (isActive) {
                gate.requestRedraw()
                Thread.sleep(0, 500_000)
            }
        }

    @Test
    fun `the cap cuts rendered frames well below an ungated terminal`() = runBlocking {
        val durationMs = 2_000L

        // Control: today's behaviour — every character invalidates, so every frame renders.
        val ungated = FakeRedrawGate()
        val ungatedWriter = driveCharacterStorm(ungated)
        Thread.sleep(durationMs)
        ungatedWriter.cancel()

        // Capped at production settings.
        val capped = FakeRedrawGate()
        val loop = driveLoop(limiter(capped, intervalMs = 66L, renderWindowMs = 20L))
        val cappedWriter = driveCharacterStorm(capped)
        Thread.sleep(durationMs)
        cappedWriter.cancel()
        loop.cancel()

        assertTrue(ungated.requests.get() > 500, "control storm too sparse: ${ungated.requests.get()}")
        assertTrue(capped.requests.get() > 500, "capped storm too sparse: ${capped.requests.get()}")
        assertTrue(
            capped.renderFrames < ungated.renderFrames,
            "cap should render fewer frames: capped=${capped.renderFrames} ungated=${ungated.renderFrames}",
        )
        // The gate is closed (66-20)/66 = 70% of each cycle and nothing invalidates Compose in
        // that span, so the capped run should land near or under half the control's frames.
        assertTrue(
            capped.renderFrames <= ungated.renderFrames * 0.75,
            "expected a substantial cut: capped=${capped.renderFrames} ungated=${ungated.renderFrames}",
        )
    }

    @Test
    fun `damage is never dropped - a gated request still renders`() = runBlocking {
        val gate = FakeRedrawGate()
        val limiter = limiter(gate, intervalMs = 20L)
        val loop = driveLoop(limiter)

        // Wait until the loop has actually closed the gate, then dirty exactly once.
        // (That the request is *withheld* while gated is asserted deterministically by the
        // flushNow test, which holds the gate closed for seconds; here the gate reopens every
        // 20ms, so only the "it eventually renders" half is race-free.)
        awaitTrue("gate never closed") { gate.isGated }
        gate.requestRedraw()

        awaitTrue("withheld damage was never flushed") { gate.redraws.get() >= 1 }
        loop.cancel()
    }

    @Test
    fun `the gate reopens every cycle so frames can actually compose`() = runBlocking {
        // ProperTerminal's captureStableRenderFrame returns null while gated and the renderer
        // falls back to the last committed frame, so a gate that never reopened would freeze
        // the terminal. Prove the loop returns to the open state repeatedly.
        val gate = FakeRedrawGate()
        val loop = driveLoop(limiter(gate, intervalMs = 20L, renderWindowMs = 5L))

        repeat(3) {
            awaitTrue("gate never closed") { gate.isGated }
            awaitTrue("gate never reopened - frames would render stale") { !gate.isGated }
        }
        loop.cancel()
    }

    @Test
    fun `flushNow reopens the gate immediately for keystroke echo`() = runBlocking {
        val gate = FakeRedrawGate()
        val limiter = limiter(gate, intervalMs = 10_000L) // gated for seconds: only flushNow can win
        val loop = driveLoop(limiter)

        awaitTrue("gate never closed") { gate.isGated }
        gate.requestRedraw()
        assertEquals(0, gate.redraws.get())

        limiter.flushNow()

        assertFalse(gate.isGated, "flushNow must leave the gate open")
        assertEquals(1, gate.redraws.get(), "flushNow must emit the withheld frame")

        // With the gate open, a subsequent keystroke echo is not delayed at all.
        gate.requestRedraw()
        assertEquals(2, gate.redraws.get())
        loop.cancel()
    }

    @Test
    fun `flushNow late in an already-open window still buys a fresh render window`() = runBlocking {
        // Reproduces the "typed and it took a beat to show up" bug. flushNow() is a no-op when
        // the gate is already open (the common case: gateLoop reopens it every cycle on its
        // own), so a keystroke landing late in that open window used to get only whatever
        // sliver of time was left before gateLoop's own timer closed it again - which can be
        // shorter than the PTY round-trip needs, stranding the echo for a full closed phase.
        // Wide enough that the blocking poll in awaitTrue (2ms granularity) and ordinary
        // coroutine dispatch jitter can't accidentally push the flush into the wrong phase.
        val gate = FakeRedrawGate()
        val intervalMs = 400L
        val renderWindowMs = 200L
        val limiter = limiter(gate, intervalMs = intervalMs, renderWindowMs = renderWindowMs)
        val loop = driveLoop(limiter)

        // Ride out one full closed phase, then catch the *start* of an open phase.
        awaitTrue("gate never closed") { gate.isGated }
        awaitTrue("gate never reopened") { !gate.isGated }

        // Flush late in that open window - only a sliver of it (~25%) is left.
        delay(renderWindowMs * 3 / 4)
        limiter.flushNow()
        assertFalse(gate.isGated, "flushNow must leave the gate open")

        // The echo doesn't land immediately (simulating the PTY round-trip). This lands well
        // past where the *original* open window would have ended (and deep into what would have
        // been the next closed phase), so the gate can only still be open because the late flush
        // bought a fresh render window rather than just the leftover sliver.
        delay(renderWindowMs / 2)
        gate.requestRedraw()
        assertTrue(!gate.isGated, "gate closed before a fresh render window elapsed after flushNow")
        assertEquals(1, gate.redraws.get(), "late-arriving echo was dropped by an early re-close")

        loop.cancel()
    }

    @Test
    fun `close leaves the terminal ungated`() = runBlocking {
        val gate = FakeRedrawGate()
        val limiter = limiter(gate, intervalMs = 10_000L)
        val loop = driveLoop(limiter)
        awaitTrue("gate never closed") { gate.isGated }

        loop.cancel()
        limiter.close()

        // A cancel mid-interval can land with the flag set; nothing else would ever clear it,
        // which would freeze the terminal permanently.
        assertFalse(gate.isGated, "close must not leave the emulator gated")
        gate.requestRedraw()
        assertEquals(1, gate.redraws.get(), "terminal must still render after close")
    }

    @Test
    fun `disabling the cap never touches the gate`() {
        val gate = FakeRedrawGate()
        val limiter = TerminalFrameLimiter(
            gate = gate,
            foregroundIntervalMillis = 0L,
            backgroundIntervalMillis = 0L,
        )
        assertFalse(limiter.isEnabled)
        limiter.start()
        limiter.flushNow()
        limiter.close()

        assertFalse(gate.isGated)
        gate.requestRedraw()
        assertEquals(1, gate.redraws.get(), "an uncapped terminal renders every request")
    }

    @Test
    fun `the gate stays open while output is idle so caret blink can repaint`() = runBlocking {
        val gate = FakeRedrawGate()
        val loop = driveLoop(limiter(gate, intervalMs = 20L, churning = { false }))

        Thread.sleep(150)
        assertFalse(gate.isGated, "idle sessions must not hold the redraw gate closed")
        assertEquals(0, gate.redraws.get(), "idle bypass should not force redraws")

        loop.cancel()
    }

    @Test
    fun `backgrounded sessions use the slower interval`() = runBlocking {
        val foreground = AtomicLong(0L) // 0 = background
        val gate = FakeRedrawGate()
        val loop = driveLoop(
            limiter(gate, intervalMs = 20L, foreground = { foreground.get() == 1L }),
        )

        val writer = launch(Dispatchers.Default) {
            while (isActive) {
                gate.requestRedraw()
                Thread.sleep(1)
            }
        }
        Thread.sleep(400)
        val background = gate.redraws.get()

        foreground.set(1L)
        gate.redraws.set(0)
        Thread.sleep(400)
        val live = gate.redraws.get()

        writer.cancel()
        loop.cancel()
        assertTrue(
            live > background,
            "foreground ($live) should render more often than background ($background)",
        )
    }

    private fun awaitTrue(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(2)
        }
        throw AssertionError(message)
    }
}
