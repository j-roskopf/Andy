package app.andy.desktop

import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import javax.swing.SwingUtilities
import javax.swing.Timer

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

    /**
     * macOS fullscreen / Space animations often finish after the last AWT COMPONENT_RESIZED.
     * A second geometry pass re-parents the Metal child once AppKit has settled.
     */
    private const val POST_FULLSCREEN_REFRESH_MS = 500

    private var deferredRefresh: Timer? = null

    /** Synchronous flag only; safe inside AWT resize dispatch. */
    fun beginWindowResize() {
        suppressingGeometry = true
        cancelDeferredRefresh()
    }

    /**
     * Called once resize has settled; all native work is deferred to a later EDT pass.
     * [scheduleDeferredRefresh] covers macOS fullscreen Space transitions that finish after
     * the last COMPONENT_RESIZED; skip it on watch teardown.
     */
    fun endWindowResize(scheduleDeferredRefresh: Boolean = true) {
        suppressingGeometry = false
        cancelDeferredRefresh()
        val wantDeferred = scheduleDeferredRefresh
        SwingUtilities.invokeLater {
            if (suppressingGeometry) return@invokeLater
            refreshNativePresentation()
            if (wantDeferred) scheduleDeferredRefresh()
        }
    }

    private fun scheduleDeferredRefresh() {
        cancelDeferredRefresh()
        deferredRefresh = Timer(POST_FULLSCREEN_REFRESH_MS) {
            deferredRefresh = null
            if (!suppressingGeometry) refreshNativePresentation()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun cancelDeferredRefresh() {
        deferredRefresh?.stop()
        deferredRefresh = null
    }

    private fun refreshNativePresentation() {
        GpuMirrorHostRegistry.allPresenters().forEach { presenter ->
            presenter.refreshGeometry()
            presenter.setVisible(true)
            presenter.repaint()
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
