package app.andy.terminal

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * The DEC 2026 synchronized-update flag, narrowed to what [TerminalFrameLimiter] drives.
 * Implemented by [ComposeTerminalDisplay.setSynchronizedUpdate]; faked in tests.
 */
fun interface SynchronizedUpdateGate {
    fun setSynchronizedUpdate(enabled: Boolean)
}

/**
 * Caps how often a BossTerm terminal is re-rendered. Compose-era replacement for the
 * former `TerminalRepaintThrottle`.
 *
 * The old throttle was a Swing [javax.swing.RepaintManager], which BossTerm's Compose
 * renderer never goes through — restoring it would cap nothing. This gates the redraw
 * request itself instead, one layer earlier.
 *
 * ### Why a cap is needed at all
 *
 * `ComposeTerminalDisplay.setCursor` calls `requestRedraw()` on **every character** the
 * emulator writes (`finishText`, `newLine`, `carriageReturn`, `cursorPosition`,
 * `cursorForward`). Measured against a live agent CLI: **2,181 redraw requests/sec**.
 *
 * BossTerm has its own limiter — `RedrawMode.INTERACTIVE` (8ms / "120fps"), backing off to
 * `HIGH_VOLUME` (50ms / 20fps) "at >100 redraws/sec". That backoff is effectively
 * unreachable: `detectAndUpdateMode()` runs *inside* the redraw processor loop, so it
 * counts redraws that already survived the 8ms debounce, and that debounce caps throughput
 * near 125/sec before any render cost is paid. Measured steady state was **38 renders/sec**,
 * firmly in `INTERACTIVE`. (`TerminalSettings.maxRefreshRate` looks like the intended knob
 * but has no consumer anywhere in BossTerm 1.2.143 — setting it does nothing.)
 *
 * At ~4.8ms of CPU per full-grid `TerminalCanvasRenderer.renderTerminal`, those 38 renders/sec
 * were **47.6% of Andy's total process CPU**.
 *
 * ### How the gate works
 *
 * `setSynchronizedUpdate` is public BossTerm API:
 *  - `true`  — `requestRedraw()` collapses to setting a `_pendingRedrawDuringSync` flag and
 *              returns. No allocation, no channel send, **no Compose invalidation**.
 *  - `false` — emits exactly **one** `requestRedraw()`, and only if the window produced damage.
 *
 * `actualRedraw` only bumps a Compose `MutableState`; the expensive `renderTerminal` is paid
 * once per Compose frame, and Compose coalesces every invalidation that lands within one
 * frame. So the render rate is set by *how much of the time the terminal is invalidated at
 * all* — closing the gate for most of each interval is what buys the reduction, not the
 * one-request-per-flush property.
 *
 * ### Why the gate is reopened for a render window
 *
 * `ProperTerminal` wraps its frame capture in `captureStableRenderFrame`, which returns
 * `null` — **without running the capture** — whenever the gate is closed, and
 * `StableRenderFrameHolder.frameFor(null)` then falls back to the *last committed frame*.
 * A gate held continuously would therefore render stale content forever. So each cycle
 * reopens the gate for at least one display frame ([RENDER_WINDOW_MS]) and only re-closes
 * once the fresh frame has had a chance to compose. Nothing goes unpainted; damage is
 * coalesced and painted once per interval instead of per character.
 *
 * ### Latency
 *
 * `requestImmediateRedraw` (keystroke echo) honours the same flag, so a permanently-held gate
 * would add up to one interval to echo latency. [flushNow] is called on user input to reopen
 * the gate immediately, preserving the old throttle's "sparse updates are never delayed"
 * property. The gate re-arms on the loop's next tick.
 *
 * ### Interaction with applications that drive DEC 2026 themselves
 *
 * The flag is a single boolean with no nesting count, so a CLI emitting BSU/ESU interleaves
 * with this loop and can have a frame ended early. The visible worst case is one torn frame —
 * which is already the status quo, since without this gate such frames are not batched at all.
 */
class TerminalFrameLimiter(
    private val gate: SynchronizedUpdateGate,
    /** Backgrounded sessions render to nothing; they only need the request churn suppressed. */
    private val foregroundProvider: () -> Boolean = { true },
    private val foregroundIntervalMillis: Long = defaultForegroundIntervalMillis(),
    private val backgroundIntervalMillis: Long = DEFAULT_BACKGROUND_INTERVAL_MS,
    /** How long the gate stays open so the flushed frame can actually compose. */
    private val renderWindowMillis: Long = defaultRenderWindowMillis(),
) : AutoCloseable {
    constructor(
        display: ComposeTerminalDisplay,
        foregroundProvider: () -> Boolean = { true },
    ) : this(SynchronizedUpdateGate(display::setSynchronizedUpdate), foregroundProvider)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** `-Dandy.terminal.repaint.fps=0` disables the cap entirely; the gate is never touched. */
    val isEnabled: Boolean get() = foregroundIntervalMillis > 0L

    @Volatile
    private var job: Job? = null

    fun start() {
        if (!isEnabled || job != null) return
        job = scope.launch { gateLoop() }
    }

    /** Extracted from [start] so tests can drive the cycle without a Compose Main dispatcher. */
    internal suspend fun gateLoop() {
        while (coroutineContext.isActive) {
            val interval = if (foregroundProvider()) foregroundIntervalMillis else backgroundIntervalMillis
            gate.setSynchronizedUpdate(true)
            // Absorb the per-character storm. Nothing invalidates Compose for this whole span.
            delay((interval - renderWindowMillis).coerceAtLeast(1L))
            // Emits one redraw iff the gated window produced damage.
            gate.setSynchronizedUpdate(false)
            // Stay open long enough for that frame to compose against a live capture,
            // otherwise ProperTerminal reuses the previous frame and the screen never advances.
            delay(renderWindowMillis)
        }
    }

    /**
     * Reopen the gate now so the next redraw is not delayed. Called on user input.
     * No-op when the cap is disabled, so callers need not branch.
     */
    fun flushNow() {
        if (!isEnabled) return
        runCatching { gate.setSynchronizedUpdate(false) }
    }

    override fun close() {
        job?.cancel()
        job = null
        // Never leave the emulator gated after we stop pumping it: a cancel mid-interval can
        // land with the flag set, and nothing would ever clear it.
        if (isEnabled) runCatching { gate.setSynchronizedUpdate(false) }
        scope.cancel()
    }

    companion object {
        /**
         * Default cap, in frames per second.
         *
         * 15 matches the cap the Swing-era throttle settled on, which was measured as the best
         * of {off, 60, 15} against a real PTY and has never drawn a latency complaint. Its 67ms
         * worst case only ever applies to streaming output — user input bypasses it via
         * [flushNow].
         *
         * Override with `-Dandy.terminal.repaint.fps=<n>` (same property as before); `0` disables.
         */
        private const val DEFAULT_FPS = 15L

        /** Backgrounded sessions render to nothing, so this only bounds request churn. */
        private const val DEFAULT_BACKGROUND_INTERVAL_MS = 500L

        /**
         * Gate-open span per cycle, sized at ~1.5 display frames.
         *
         * It must exceed one frame plus the EDT hop the redraw processor needs, or the flushed
         * frame composes after the gate has re-closed and `captureStableRenderFrame` hands back
         * a stale frame (self-correcting — the next cycle flushes again — but visibly laggy).
         * It must not greatly exceed one frame either: Compose renders every frame the window
         * spans, so an over-long window on a high-refresh panel silently restores the cost this
         * class exists to remove. Hence scaling with the panel rather than a constant: 25ms at
         * 60Hz, ~10ms at 144Hz, both landing near one rendered frame per cycle.
         */
        private fun defaultRenderWindowMillis(): Long {
            val hz = runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.displayMode.refreshRate
            }.getOrNull()?.takeIf { it > 0 } ?: 60
            return (1_500L / hz).coerceIn(8L, 25L)
        }

        private fun defaultForegroundIntervalMillis(): Long {
            val fps = System.getProperty("andy.terminal.repaint.fps")?.toLongOrNull() ?: DEFAULT_FPS
            return if (fps <= 0L) 0L else (1_000L / fps).coerceAtLeast(1L)
        }
    }
}
