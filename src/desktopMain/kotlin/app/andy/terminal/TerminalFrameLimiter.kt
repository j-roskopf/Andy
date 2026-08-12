package app.andy.terminal

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * property.
 *
 * A keystroke's echo does not arrive synchronously with [flushNow] — the byte still has to
 * round-trip through the PTY line discipline and back before BossTerm sees it as inbound damage.
 * Toggling the flag once and letting the ambient loop's own timer decide when to re-close it
 * is not enough: if [flushNow] lands just as [gateLoop] was about to close the gate anyway (its
 * own timer fires moments later), the reopened window can be far shorter than one render
 * window, and the echo arrives after the gate has already slammed shut — stranding it until the
 * *next* cycle. That is the "I typed and it took a beat to show up" case, and it gets worse
 * under churn since that is when [gateLoop] is actively cycling the gate rather than leaving it
 * open. [flushWake] interrupts [gateLoop]'s current wait so a flush always buys a fresh
 * [renderWindowMillis] of open gate, regardless of where in the cycle it lands.
 *
 * ### Cursor blink
 *
 * BossTerm's caret blink toggles a Compose `MutableState` on a timer — it does **not** call
 * `requestRedraw()`. While the gate is closed with no pending damage, `captureStableRenderFrame`
 * hands back the last committed bitmap and the caret appears frozen. The gate therefore stays
 * open whenever [churningProvider] reports no recent PTY output; streaming sessions still
 * engage the cap for the per-character storm.
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
    /** When false the gate stays open so BossTerm's caret-blink Compose state can repaint. */
    private val churningProvider: () -> Boolean = { true },
    private val foregroundIntervalMillis: Long = defaultForegroundIntervalMillis(),
    private val backgroundIntervalMillis: Long = DEFAULT_BACKGROUND_INTERVAL_MS,
    /** How long the gate stays open so the flushed frame can actually compose. */
    private val renderWindowMillis: Long = defaultRenderWindowMillis(),
) : AutoCloseable {
    constructor(
        display: ComposeTerminalDisplay,
        foregroundProvider: () -> Boolean = { true },
        churningProvider: () -> Boolean = { true },
    ) : this(
        SynchronizedUpdateGate(display::setSynchronizedUpdate),
        foregroundProvider,
        churningProvider,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Wakes a closed-phase wait in [gateLoop] early; conflated so bursts of flushes collapse to one. */
    private val flushWake = Channel<Unit>(Channel.CONFLATED)

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
            if (!churningProvider()) {
                // Idle: caret blink is Compose-driven and never sets the pending-redraw flag.
                gate.setSynchronizedUpdate(false)
                delay(interval)
                continue
            }
            // Drop any flush requested while the gate was already open — it already got what it
            // wanted — so only a flush during *this* closed phase wakes the wait below early.
            while (flushWake.tryReceive().isSuccess) { /* drain stale wakes */ }
            gate.setSynchronizedUpdate(true)
            // Absorb the per-character storm. Nothing invalidates Compose for this whole span,
            // unless flushNow wakes it early for a keystroke.
            withTimeoutOrNull((interval - renderWindowMillis).coerceAtLeast(1L)) { flushWake.receive() }
            // Emits one redraw iff the gated window produced damage.
            gate.setSynchronizedUpdate(false)
            // Stay open long enough for that frame to compose against a live capture,
            // otherwise ProperTerminal reuses the previous frame and the screen never advances.
            openForRenderWindow()
        }
    }

    /**
     * Holds the gate open for [renderWindowMillis]; a flush landing anywhere in that span resets
     * the countdown to a full window again. Without the reset, a flush arriving late in the
     * window (gate already open, so [flushNow] itself is a no-op) would only get whatever time
     * was left before the next cycle's closed phase — which can be shorter than a PTY round-trip
     * needs, stranding the echo for a full cycle. Typing is sparse enough that this rarely chains
     * more than once or twice in practice.
     */
    private suspend fun openForRenderWindow() {
        var remaining = renderWindowMillis
        while (withTimeoutOrNull(remaining) { flushWake.receive() } != null) {
            remaining = renderWindowMillis
        }
    }

    /**
     * Reopen the gate now so the next redraw is not delayed, and guarantee it stays open for at
     * least one full [renderWindowMillis] — not just until [gateLoop]'s own timer next fires.
     * Called on user input. No-op when the cap is disabled, so callers need not branch.
     *
     * Keystroke echo arrives asynchronously (PTY round-trip), so a bare gate toggle can lose the
     * race: if [gateLoop] was about to close the gate anyway, the reopened window can be far
     * shorter than [renderWindowMillis] and the echo lands after the gate has shut again.
     * Waking [gateLoop]'s wait makes the reopened window consistent instead.
     */
    fun flushNow() {
        if (!isEnabled) return
        runCatching { gate.setSynchronizedUpdate(false) }
        flushWake.trySend(Unit)
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
