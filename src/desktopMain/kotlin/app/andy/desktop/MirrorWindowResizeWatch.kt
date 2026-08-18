package app.andy.desktop

import java.awt.AWTEvent
import java.awt.Dialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Freezes Metal mirror geometry while **any** Andy window is being live-resized.
 *
 * Pop-out mirror windows host their own [app.andy.desktop.service.mirror.GpuMirrorPresenter], so a
 * pop-out resize drags AppKit geometry from the EDT exactly like a main-window resize does — and
 * the main thread is busy inside AWT resize callbacks the whole time. Watching only the main window
 * left pop-outs unguarded and froze the app on the first resize drag.
 *
 * A Toolkit-wide [AWTEventListener] (rather than per-window listeners) is deliberate: it sees
 * COMPONENT_RESIZED before it reaches the mirror SwingPanel peers, so the guard is already up when
 * their own listeners run.
 */
internal class MirrorWindowResizeWatch(
    private val settleMillis: Long = SETTLE_MILLIS,
    private val onResizingChanged: (Boolean) -> Unit = {},
) {
    private var listener: AWTEventListener? = null
    private var settleJob: Job? = null

    fun install(scope: CoroutineScope) {
        if (listener != null) return
        val awtListener = AWTEventListener { event -> onAwtEvent(event, scope) }
        listener = awtListener
        Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.COMPONENT_EVENT_MASK)
    }

    fun uninstall() {
        listener?.let(Toolkit.getDefaultToolkit()::removeAWTEventListener)
        listener = null
        settleJob?.cancel()
        settleJob = null
        onResizingChanged(false)
        // Drop the guard without a post-fullscreen deferred refresh — hosts may already be gone.
        MirrorPresentationGuard.endWindowResize(scheduleDeferredRefresh = false)
    }

    private fun onAwtEvent(event: AWTEvent, scope: CoroutineScope) {
        if (event.id != ComponentEvent.COMPONENT_RESIZED || !isTopLevelWindow(event.source)) return
        // Synchronous flag only — this runs inside AWT resize dispatch, where native calls deadlock.
        MirrorPresentationGuard.beginWindowResize()
        onResizingChanged(true)
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(settleMillis)
            onResizingChanged(false)
            MirrorPresentationGuard.endWindowResize()
        }
    }

    /**
     * Frames and dialogs only. Heavyweight Swing popups (menus, tooltips) are plain [java.awt.Window]s
     * that resize as they open — treating those as a window resize would blank the mirror on every
     * dropdown.
     */
    private fun isTopLevelWindow(source: Any?): Boolean = source is Frame || source is Dialog

    private companion object {
        const val SETTLE_MILLIS = 350L
    }
}
