package app.andy.desktop.service.mirror

import java.awt.Canvas
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

    fun register(candidate: Canvas) {
        if (!hosts.contains(candidate)) {
            hosts.add(candidate)
        }
        // Prefer the newest realized surface (pop-out when opened) for geometry tracking.
        if (NativeMirrorJni.isMetalInlineOverlayOpen()) {
            // removeMetalLayer() hides the overlay when the last host detaches; restore it when
            // Live remounts without forcing a full mirror reconnect.
            NativeMirrorJni.setInlineOverlayVisible(true)
            NativeMirrorJni.updateMetalLayerGeometry(candidate)
            NativeMirrorJni.repaintLatestFrame()
        }
    }

    fun unregister(candidate: Canvas) {
        hosts.remove(candidate)
    }

    fun current(): Canvas? = hosts.lastOrNull { it.isDisplayable }

    fun otherDisplayable(excluding: Canvas): Canvas? =
        hosts.lastOrNull { it !== excluding && it.isDisplayable }
}
