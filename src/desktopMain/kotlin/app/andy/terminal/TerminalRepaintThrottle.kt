package app.andy.terminal

import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.Rectangle
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Caps how often terminal widgets are pushed through the Swing paint pipeline.
 *
 * KetraTerm already repaints only the rows that changed, but a dirty region of any size
 * still costs a full blit of the widget's Metal layer (`sun.java2d.metal.MTLLayer`) and a
 * full-window composite in WindowServer. Agent CLIs redraw a spinner continuously while a
 * turn runs, so the repaint rate — not the repainted area — is what the GPU pays for, and
 * on a large high-refresh display that was the single largest slice of Andy's CPU while a
 * chat was streaming.
 *
 * Sparse updates are not delayed at all: a repaint arriving more than [intervalMillis]
 * after the last flush goes straight through, which is the keystroke-echo case. Only a
 * widget already repainting faster than the cap is coalesced, and then its dirty regions
 * are unioned rather than dropped, so nothing goes unpainted — it is painted later, once,
 * instead of sooner, repeatedly.
 *
 * Non-terminal components are delegated untouched: Compose renders through Skiko, not this
 * pipeline, so throttling it here would slow the rest of the UI for nothing.
 */
class TerminalRepaintThrottle(
    private val intervalMillis: Long = defaultIntervalMillis(),
) : RepaintManager() {
    private val lock = Any()
    private val pending = LinkedHashMap<JComponent, Rectangle>()
    private var lastFlushNanos: Long = 0L

    /**
     * Whether a component sits under a [SwingTerminal]. Ancestry is stable in practice and
     * this is on the repaint path, so it is resolved once per component. A stale *negative*
     * only forgoes throttling, which is the previous behaviour; nothing breaks.
     */
    private val throttled = WeakHashMap<JComponent, Boolean>()

    private val timer = Timer(intervalMillis.toInt()) { flush() }.apply {
        isRepeats = false
        isCoalesce = true
    }

    override fun addDirtyRegion(c: JComponent, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || !isTerminalComponent(c)) {
            super.addDirtyRegion(c, x, y, w, h)
            return
        }
        val dueInMillis: Long
        synchronized(lock) {
            pending.merge(c, Rectangle(x, y, w, h)) { existing, added -> existing.union(added) }
            val sinceFlushMillis = (System.nanoTime() - lastFlushNanos) / 1_000_000L
            dueInMillis = (intervalMillis - sinceFlushMillis).coerceAtLeast(0L)
        }
        if (dueInMillis == 0L) {
            // Idle long enough that this repaint owes nothing — paint it now and keep
            // interactive latency identical to an unthrottled manager.
            if (SwingUtilities.isEventDispatchThread()) flush() else SwingUtilities.invokeLater(::flush)
        } else {
            SwingUtilities.invokeLater {
                // Restarting resets the countdown, so schedule only when idle: a stream of
                // repaints must still flush every intervalMillis, not be pushed back forever.
                if (!timer.isRunning) {
                    timer.initialDelay = dueInMillis.toInt()
                    timer.start()
                }
            }
        }
    }

    private fun flush() {
        val batch: List<Map.Entry<JComponent, Rectangle>>
        synchronized(lock) {
            if (pending.isEmpty()) return
            batch = pending.entries.toList()
            pending.clear()
            lastFlushNanos = System.nanoTime()
        }
        for ((component, rect) in batch) {
            super.addDirtyRegion(component, rect.x, rect.y, rect.width, rect.height)
        }
    }

    private fun isTerminalComponent(c: JComponent): Boolean {
        throttled[c]?.let { return it }
        val resolved = c is SwingTerminal ||
            SwingUtilities.getAncestorOfClass(SwingTerminal::class.java, c) != null
        throttled[c] = resolved
        return resolved
    }

    companion object {
        /**
         * Default cap, in frames per second. Terminal output carries no motion that needs
         * more, and 60 keeps the worst-case added latency to a single 60Hz frame even on a
         * faster panel. Override with `-Dandy.terminal.repaint.fps=<n>`; `0` disables.
         */
        private const val DEFAULT_FPS = 60L

        private val installed = AtomicBoolean(false)

        private fun defaultIntervalMillis(): Long {
            val fps = System.getProperty("andy.terminal.repaint.fps")?.toLongOrNull() ?: DEFAULT_FPS
            return if (fps <= 0L) 0L else (1_000L / fps).coerceAtLeast(1L)
        }

        /** Install once, on the EDT — [RepaintManager.setCurrentManager] is per-AppContext. */
        fun ensureInstalled() {
            if (defaultIntervalMillis() <= 0L) return
            if (!installed.compareAndSet(false, true)) return
            onSwingEdt {
                RepaintManager.setCurrentManager(TerminalRepaintThrottle())
            }
        }
    }
}
