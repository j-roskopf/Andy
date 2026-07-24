package app.andy.desktop

import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import javax.swing.SwingUtilities

/**
 * Pauses Metal geometry while the main Andy window is being resized.
 *
 * [beginWindowResize] must not touch JNI/AppKit — it can run from an [java.awt.event.AWTEventListener]
 * before COMPONENT_RESIZED reaches mirror SwingPanel peers. Calling orderOut/setFrame there deadlocks
 * the EDT and the UI never recovers.
 */
internal object MirrorPresentationGuard {
    @Volatile
    var suppressingGeometry: Boolean = false
        private set

    /** Synchronous flag only; safe inside AWT resize dispatch. */
    fun beginWindowResize() {
        suppressingGeometry = true
    }

    /** Called once resize has settled; all native work is deferred to a later EDT pass. */
    fun endWindowResize() {
        suppressingGeometry = false
        SwingUtilities.invokeLater {
            if (suppressingGeometry) return@invokeLater
            refreshNativePresentation()
        }
    }

    private fun refreshNativePresentation() {
        GpuMirrorHostRegistry.allPresenters().forEach { presenter ->
            presenter.refreshGeometry()
            presenter.setVisible(true)
        }
        NativeMirrorHostRegistry.current()?.let { host ->
            NativeMirrorJni.updateMetalLayerGeometry(host)
            if (NativeMirrorJni.isMetalInlineOverlayOpen()) {
                NativeMirrorJni.setInlineOverlayVisible(true)
                NativeMirrorJni.repaintLatestFrame()
            }
        }
    }
}
