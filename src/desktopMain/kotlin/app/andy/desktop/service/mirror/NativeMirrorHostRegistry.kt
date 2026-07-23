package app.andy.desktop.service.mirror

import java.awt.Canvas
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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

    fun register(candidate: Canvas) {
        if (!hosts.contains(candidate)) {
            hosts.add(candidate)
        }
        // Only Metal hosts should rebind the shared presenter. CPU-only canvases must not steal
        // the overlay from an active GPU mirror (e.g. iOS pop-out while Android CPU pop-out opens).
        if (NativeMirrorJni.isMetalInlineOverlayOpen() && candidate in metalHosts) {
            NativeMirrorJni.setInlineOverlayVisible(true)
            NativeMirrorJni.updateMetalLayerGeometry(candidate)
            NativeMirrorJni.repaintLatestFrame()
        }
    }

    fun unregister(candidate: Canvas) {
        hosts.remove(candidate)
        metalHosts.remove(candidate)
    }

    fun current(): Canvas? = hosts.lastOrNull { it.isDisplayable }

    /** Test-only snapshot of registered hosts for presentation regression tests. */
    internal fun registeredHostsForTests(): List<Canvas> = hosts.toList()

    fun otherDisplayable(excluding: Canvas): Canvas? =
        hosts.lastOrNull { it !== excluding && it.isDisplayable }

    fun markHostsMetalPresentation(canvas: Canvas, hostsMetal: Boolean) {
        if (hostsMetal) {
            metalHosts.add(canvas)
        } else {
            metalHosts.remove(canvas)
        }
    }
}
