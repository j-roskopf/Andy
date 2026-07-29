package app.andy.terminal

import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.Component
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener

/**
 * Whether a wheel event is scrolling toward older terminal history.
 *
 * Matches KetraTerm's `wheelScrollLines` sign: negate [MouseWheelEvent.getPreciseWheelRotation],
 * then treat positive values as "up" (toward history / UP key on the alt screen).
 */
internal fun terminalWheelTowardHistory(event: MouseWheelEvent): Boolean {
    val lines = terminalWheelScrollDelta(event)
    return lines > 0.0
}

/** Wheel delta in terminal line units, matching KetraTerm's own wheel scaling. */
internal fun terminalWheelScrollDelta(
    event: MouseWheelEvent,
    visibleRows: Int = 1,
): Double {
    val rowsPerRotation = when (event.scrollType) {
        MouseWheelEvent.WHEEL_UNIT_SCROLL -> event.scrollAmount.coerceAtLeast(1)
        MouseWheelEvent.WHEEL_BLOCK_SCROLL -> visibleRows.coerceAtLeast(1)
        else -> 1
    }
    return -event.preciseWheelRotation * rowsPerRotation
}

/** True when [component] is [ancestor] or nested beneath it. */
internal fun isComponentWithin(component: Component?, ancestor: Component): Boolean {
    var current = component
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent
    }
    return false
}

/**
 * One wheel-up nudge received while a live-history replay is being mounted.
 *
 * Replaying the sum of every event after an asynchronous mount makes the terminal look
 * frozen and then jump several screens at once. Keep only the strongest pending event so
 * the first rendered history frame moves immediately but stays spatially understandable.
 */
internal class PendingHistoryScroll {
    private var delta: Double = 0.0

    @Synchronized
    fun add(value: Double) {
        if (value > delta) delta = value
    }

    @Synchronized
    fun drain(): Double = delta.also { delta = 0.0 }

    @Synchronized
    fun clear() {
        delta = 0.0
    }
}

/** What Andy should do with a wheel gesture over a live / history-peek terminal. */
internal enum class LiveTerminalWheelAction {
    /** Apply [SwingTerminal.scrollViewportBy]. */
    ScrollViewport,

    /** Alt-screen lock: open Andy's flushed scrollback peek. */
    OpenHistoryPeek,

    /** History peek is at its live edge; resume the live PTY view. */
    ReturnToLive,

    /** No-op, but the event should still be consumed (block alt-screen UP keys). */
    Consume,
}

/**
 * Decide wheel routing for agent terminals.
 *
 * Live agent TUIs sit on the alt screen with [historySize] == 0, so
 * [SwingTerminal.scrollViewportBy] cannot reveal older output. Wheel-up then
 * opens Andy's flushed scrollback peek instead of feeling locked.
 */
internal fun resolveLiveTerminalWheelAction(
    delta: Double,
    historySize: Int,
    atLiveViewport: Boolean,
    canOpenHistoryPeek: Boolean,
    canReturnToLive: Boolean,
): LiveTerminalWheelAction {
    if (delta == 0.0) return LiveTerminalWheelAction.Consume
    val towardHistory = delta > 0.0
    return if (towardHistory) {
        when {
            historySize > 0 -> LiveTerminalWheelAction.ScrollViewport
            canOpenHistoryPeek -> LiveTerminalWheelAction.OpenHistoryPeek
            else -> LiveTerminalWheelAction.Consume
        }
    } else {
        when {
            canReturnToLive && atLiveViewport -> LiveTerminalWheelAction.ReturnToLive
            else -> LiveTerminalWheelAction.ScrollViewport
        }
    }
}

/**
 * Owns wheel input on a live or history-peek [terminal].
 *
 * KetraTerm's own wheel listener turns alt-screen wheel-up into UP keys (prompt
 * history). An AWT [java.awt.EventQueue] interceptor is unreliable under Compose
 * Desktop's SwingPanel, so Andy displaces KetraTerm's listeners and routes the
 * gesture itself — scroll viewport, open history peek, or return to live.
 */
internal class LiveTerminalWheelHandler(
    private val terminal: SwingTerminal,
    private val onOpenHistoryPeek: ((Double) -> Unit)? = null,
    private val onReturnToLive: (() -> Unit)? = null,
) : MouseWheelListener {
    private val displaced: Array<MouseWheelListener> = terminal.mouseWheelListeners

    init {
        displaced.forEach(terminal::removeMouseWheelListener)
        terminal.addMouseWheelListener(this)
    }

    override fun mouseWheelMoved(event: MouseWheelEvent) {
        val delta = terminalWheelScrollDelta(
            event = event,
            visibleRows = runCatching { terminal.visibleGridSize().height }.getOrDefault(1),
        )
        val state = runCatching { terminal.viewportState() }.getOrNull()
        when (
            resolveLiveTerminalWheelAction(
                delta = delta,
                historySize = state?.historySize ?: 0,
                atLiveViewport = state?.isAtLiveViewport ?: true,
                canOpenHistoryPeek = onOpenHistoryPeek != null,
                canReturnToLive = onReturnToLive != null,
            )
        ) {
            LiveTerminalWheelAction.ScrollViewport -> {
                if (delta != 0.0) terminal.scrollViewportBy(delta)
            }
            LiveTerminalWheelAction.OpenHistoryPeek -> onOpenHistoryPeek?.invoke(delta)
            LiveTerminalWheelAction.ReturnToLive -> onReturnToLive?.invoke()
            LiveTerminalWheelAction.Consume -> Unit
        }
        event.consume()
    }

    fun uninstall() {
        terminal.removeMouseWheelListener(this)
        displaced.forEach(terminal::addMouseWheelListener)
    }
}
