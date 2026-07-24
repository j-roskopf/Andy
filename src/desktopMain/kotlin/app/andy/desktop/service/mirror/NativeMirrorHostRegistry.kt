package app.andy.desktop.service.mirror

import java.awt.Canvas
import java.awt.Window
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * Realized heavyweight Live/pop-out canvases that can host the in-process Metal presenter.
 *
 * More than one Canvas can exist at once (main Live pane + View → Pop Out Mirror). The registry
 * keeps all of them so closing the pop-out can rebind presentation to the Live host instead of
 * destroying VideoToolbox/Metal and leaving the inline pane blank.
 */
internal object NativeMirrorHostRegistry {
    private val hosts = CopyOnWriteArrayList<Canvas>()
    private val metalHosts = Collections.newSetFromMap(ConcurrentHashMap<Canvas, Boolean>())
    private val fillHostMetalHosts = Collections.newSetFromMap(ConcurrentHashMap<Canvas, Boolean>())
    @Volatile private var presentationOwner: Canvas? = null

    fun register(candidate: Canvas) {
        if (!hosts.contains(candidate)) {
            hosts.add(candidate)
        }
        if (presentationOwner === candidate || shouldRebindOnRegister(candidate)) {
            rebindPresentation(candidate)
        }
    }

    fun unregister(candidate: Canvas) {
        val closedWindow = SwingUtilities.getWindowAncestor(candidate)
        hosts.remove(candidate)
        metalHosts.remove(candidate)
        fillHostMetalHosts.remove(candidate)
        if (presentationOwner === candidate) {
            presentationOwner = null
            promoteFallbackExcluding(closedWindow)
        }
    }

    /** Moves the shared Metal presenter to [window]'s mirror host (pop-out). */
    fun promoteWindow(window: Window) {
        hostInWindow(window)?.let(::promote)
    }

    /** Returns presentation to the main Live host when a pop-out [window] closes. */
    fun relinquishWindow(window: Window) {
        if (presentationOwner != null && SwingUtilities.getWindowAncestor(presentationOwner) == window) {
            presentationOwner = null
            promoteFallbackExcluding(window)
        }
    }

    fun promote(candidate: Canvas) {
        presentationOwner = candidate
        rebindPresentation(candidate)
    }

    fun current(): Canvas? =
        presentationOwner?.takeIf { it.isDisplayable }
            ?: fallbackHost()

    fun hostInWindow(window: Window): Canvas? =
        hosts.lastOrNull { canvas ->
            canvas.isDisplayable && SwingUtilities.getWindowAncestor(canvas) == window
        }

    /** Test-only snapshot of registered hosts for presentation regression tests. */
    internal fun registeredHostsForTests(): List<Canvas> = hosts.toList()

    fun otherDisplayable(excluding: Canvas): Canvas? =
        hosts.lastOrNull { it !== excluding && it.isDisplayable }

    fun markHostsMetalPresentation(canvas: Canvas, hostsMetal: Boolean, fillHost: Boolean = false) {
        if (hostsMetal) {
            metalHosts.add(canvas)
            if (fillHost) {
                fillHostMetalHosts.add(canvas)
            } else {
                fillHostMetalHosts.remove(canvas)
            }
        } else {
            metalHosts.remove(canvas)
            fillHostMetalHosts.remove(canvas)
        }
    }

    fun fillsHost(canvas: Canvas): Boolean = canvas in fillHostMetalHosts

    fun clearPopOutPresentation() {
        presentationOwner = null
        NativeMirrorJni.setInlineOverlayVisible(false)
        fallbackHost()?.let(::rebindPresentation)
    }

    private fun fallbackHost(): Canvas? = hosts.lastOrNull { it.isDisplayable }

    private fun promoteFallbackExcluding(excludeWindow: Window?) {
        hosts.firstOrNull { canvas ->
            canvas.isDisplayable && SwingUtilities.getWindowAncestor(canvas) != excludeWindow
        }?.let(::promote)
            ?: fallbackHost()?.let(::rebindPresentation)
    }

    private fun shouldRebindOnRegister(candidate: Canvas): Boolean =
        NativeMirrorJni.isMetalInlineOverlayOpen() &&
            candidate in metalHosts &&
            presentationOwner == null

    private fun rebindPresentation(candidate: Canvas) {
        if (!NativeMirrorJni.isMetalInlineOverlayOpen() || candidate !in metalHosts) return
        NativeMirrorJni.setInlineOverlayVisible(true)
        NativeMirrorJni.updateMetalLayerGeometry(candidate)
        NativeMirrorJni.repaintLatestFrame()
    }
}
