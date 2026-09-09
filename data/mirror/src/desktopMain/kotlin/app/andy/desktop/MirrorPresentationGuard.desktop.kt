package app.andy.desktop

import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Blocks the *synchronous* AppKit calls in the mirror presenters while an Andy window is live-resized.
 *
 * [beginWindowResize] must not touch JNI/AppKit — it can run from an [java.awt.event.AWTEventListener]
 * before COMPONENT_RESIZED reaches mirror SwingPanel peers. Calling orderOut/setFrame there deadlocks
 * the EDT and the UI never recovers.
 *
 * Only presenter *attach* is suppressed, because opening an overlay window reaches AppKit through a
 * main-thread `dispatch_sync` (`run_on_main`) — and AWT's own resize handling hops main→EDT, so a
 * mid-drag dispatch_sync from the EDT deadlocks both. Geometry pushes are deliberately **not**
 * suppressed: they only store pending values and coalesce onto the main queue with `dispatch_async`,
 * so the Metal overlay can follow the drag instead of freezing at its pre-resize rect and snapping
 * into place after the settle delay.
 */
internal object MirrorPresentationGuard {
    @Volatile
    var suppressingAttach: Boolean = false
        private set

    /**
     * macOS fullscreen / Space animations often finish after the last AWT COMPONENT_RESIZED.
     * A second geometry pass re-parents the Metal child once AppKit has settled.
     */
    private const val POST_FULLSCREEN_REFRESH_MS = 500

    private var deferredRefresh: Timer? = null

    /** Synchronous flag only; safe inside AWT resize dispatch. */
    fun beginWindowResize() {
        suppressingAttach = true
        cancelDeferredRefresh()
    }

    /**
     * Called once resize has settled; all native work is deferred to a later EDT pass.
     * [scheduleDeferredRefresh] covers macOS fullscreen Space transitions that finish after
     * the last COMPONENT_RESIZED; skip it on watch teardown.
     */
    fun endWindowResize(scheduleDeferredRefresh: Boolean = true) {
        suppressingAttach = false
        cancelDeferredRefresh()
        val wantDeferred = scheduleDeferredRefresh
        SwingUtilities.invokeLater {
            if (suppressingAttach) return@invokeLater
            refreshNativePresentation()
            if (wantDeferred) scheduleDeferredRefresh()
        }
    }

    private fun scheduleDeferredRefresh() {
        cancelDeferredRefresh()
        deferredRefresh = Timer(POST_FULLSCREEN_REFRESH_MS) {
            deferredRefresh = null
            if (!suppressingAttach) refreshNativePresentation()
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
