package app.andy.desktop.service.mirror

import app.andy.desktop.MirrorPresentationGuard
import app.andy.desktop.nsWindowNumber
import app.andy.service.MirrorFrame
import java.awt.Canvas
import java.awt.Component
import java.awt.Window
import javax.swing.SwingUtilities

/** One VideoToolbox decode stream; may fan out to multiple [GpuMirrorPresenter] instances. */
class GpuMirrorPipeline private constructor(
    val decoderId: Long,
) : AutoCloseable {
    private var closed = false

    fun createPresenter(host: Canvas? = null): GpuMirrorPresenter? {
        if (closed) return null
        if (host != null) {
            GpuMirrorHostRegistry.presenterFor(host)?.let { existing ->
                if (existing.decoderId == decoderId) return existing
                existing.close()
            }
        }
        GpuMirrorHostRegistry.unattachedPresenterForDecoder(decoderId)?.let { return it }
        val presenterId = GpuMirrorJni.createPresenter(decoderId)
        if (presenterId == 0L) return null
        return GpuMirrorPresenter(this, presenterId)
    }

    fun consumeH264(packet: ByteArray): Boolean = !closed && GpuMirrorJni.consumeH264(decoderId, packet)

    fun resetDecoderStream() {
        if (!closed) GpuMirrorJni.resetDecoderStream(decoderId)
    }

    fun presentSolidBgra(width: Int, height: Int, blue: Int, green: Int, red: Int, alpha: Int = 255): Boolean =
        !closed && GpuMirrorJni.presentSolidBgra(decoderId, width, height, blue, green, red, alpha)

    fun recordInput() {
        if (!closed) GpuMirrorJni.recordInput(decoderId)
    }

    fun recordTransportIngress() {
        if (!closed) GpuMirrorJni.recordTransportIngress(decoderId)
    }

    fun framesPresented(): Long = if (closed) 0L else GpuMirrorJni.framesPresented(decoderId)

    fun hasDecodedFrame(): Boolean = !closed && GpuMirrorJni.hasDecodedFrame(decoderId)

    fun isHardwareReady(): Boolean = !closed && GpuMirrorJni.isHardwareReady(decoderId)

    fun latestFrameSize(): Pair<Int, Int>? =
        if (closed) null else GpuMirrorJni.latestFrameSize(decoderId)

    /** Samples the latest decoded frame as ARGB for bug/recording capture backup. */
    fun copyLatestFrameArgb(): MirrorFrame? =
        if (closed) null else GpuMirrorJni.copyLatestFrameArgb(decoderId)

    fun bindIosCapture(simulator: Boolean) {
        if (!closed) GpuMirrorJni.bindIosDecoder(decoderId, simulator)
    }

    fun unbindIosCapture() {
        // Only release an iOS routing slot if *this* decoder still owns it. Closing an
        // unrelated (e.g. Android) pipeline must not blank a live iOS mirror bound elsewhere.
        GpuMirrorJni.clearIosDecoder(decoderId)
    }

    fun setContentSize(width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        GpuMirrorHostRegistry.presentersForDecoder(decoderId).forEach {
            it.setContentSize(width, height)
        }
    }

    fun repaintAll() {
        if (closed) return
        GpuMirrorHostRegistry.presentersForDecoder(decoderId).forEach { it.repaint() }
    }

    /**
     * Forces every presenter to re-resolve its parent window and geometry. Used after a device
     * switch, once the previous device's overlay window has closed, so a presenter that briefly
     * mis-parented onto that closing window re-attaches to the real host window.
     */
    fun refreshAllGeometry() {
        if (closed) return
        GpuMirrorHostRegistry.presentersForDecoder(decoderId).forEach { it.refreshGeometry() }
    }

    override fun close() {
        if (closed) return
        closed = true
        unbindIosCapture()
        GpuMirrorHostRegistry.presentersForDecoder(decoderId).toList().forEach { it.close() }
        GpuMirrorHostRegistry.detachDecoder(decoderId)
        GpuMirrorJni.destroyDecoder(decoderId)
    }

    companion object {
        fun create(): GpuMirrorPipeline? {
            if (!GpuMirrorJni.isAvailable()) return null
            val decoderId = GpuMirrorJni.createDecoder()
            if (decoderId == 0L) return null
            return GpuMirrorPipeline(decoderId)
        }
    }
}

/** One borderless Metal overlay bound to a Swing Canvas host. */
class GpuMirrorPresenter internal constructor(
    private val pipeline: GpuMirrorPipeline,
    val presenterId: Long,
) : AutoCloseable {
    private var attachedHost: Canvas? = null
    private var fillHost = false
    private var contentWidth = 0
    private var contentHeight = 0
    private var geometryUpdateScheduled = false
    private var lastGeometryKey: String? = null
    private var visibleRequested = true
    /** Last value actually pushed to the hub, so geometry passes skip redundant JNI hops. */
    private var visibleApplied: Boolean? = null

    fun attach(host: Canvas, fillHost: Boolean): Boolean {
        if (attachedHost === host && this.fillHost == fillHost) {
            updateGeometry(host)
            return true
        }
        warmDetach()
        this.fillHost = fillHost
        if (!GpuMirrorJni.openPresenterOverlay(presenterId)) return false
        GpuMirrorJni.setPresenterFillHost(presenterId, fillHost)
        attachedHost = host
        GpuMirrorHostRegistry.registerPresenter(host, this)
        lastGeometryKey = null
        visibleApplied = null
        val finalizeAttach = {
            lastGeometryKey = null
            // Clear desktop suppress before showing — setVisible alone must not, or
            // geometry updates during VD switches remount the floating overlay.
            GpuMirrorJni.resumeAfterDesktopSwitch()
            commitGeometry(host)
            visibleApplied = null
            visibleRequested = true
            applyVisible(true)
            GpuMirrorJni.repaintPresenter(presenterId)
        }
        if (SwingUtilities.isEventDispatchThread()) {
            finalizeAttach()
        } else {
            SwingUtilities.invokeAndWait { finalizeAttach() }
        }
        return true
    }

    fun isAttachedTo(host: Canvas): Boolean = attachedHost === host

    fun detach() {
        warmDetach()
    }

    /** Hides the overlay and unregisters the host without destroying the native presenter. */
    private fun warmDetach() {
        val host = attachedHost
        attachedHost = null
        if (host != null && GpuMirrorHostRegistry.presenterFor(host) === this) {
            GpuMirrorHostRegistry.unregisterPresenter(host)
        }
        visibleRequested = false
        visibleApplied = null
        applyVisible(false)
    }

    fun setVisible(visible: Boolean) {
        visibleRequested = visible
        applyVisible(visible)
        // Do not refresh geometry here. updateGeometry → setFrame flashes the black Canvas
        // under the mouse-transparent Metal overlay (every click looked like a black blink).
        // Occlusion resume and attach/resize paths call updateGeometry explicitly.
    }

    /** Re-front the Metal overlay without resizing (safe on mouse press / focus). */
    fun bringToFront() {
        visibleRequested = true
        // Explicit re-front request: always reach the hub, even if visibility never changed.
        visibleApplied = null
        applyVisible(true)
    }

    /** Pushes visibility only when it changed; the hub call is otherwise pure JNI churn. */
    private fun applyVisible(visible: Boolean) {
        if (visibleApplied == visible) return
        visibleApplied = visible
        GpuMirrorJni.setPresenterVisible(presenterId, visible)
    }

    fun setContentSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (contentWidth == width && contentHeight == height) return
        contentWidth = width
        contentHeight = height
        GpuMirrorJni.setPresenterContentSize(presenterId, width, height)
        attachedHost?.let(::updateGeometry)
    }

    fun updateGeometry(component: Component) {
        if (MirrorPresentationGuard.suppressingGeometry) return
        if (!component.isDisplayable) return
        if (geometryUpdateScheduled) return
        geometryUpdateScheduled = true
        SwingUtilities.invokeLater {
            geometryUpdateScheduled = false
            val host = attachedHost ?: return@invokeLater
            if (host !== component) return@invokeLater
            applyGeometry(host)
        }
    }

    private fun commitGeometry(host: Canvas) {
        if (SwingUtilities.isEventDispatchThread()) {
            applyGeometry(host)
        } else {
            SwingUtilities.invokeLater { applyGeometry(host) }
        }
    }

    fun repaint() {
        GpuMirrorJni.repaintPresenter(presenterId)
    }

    fun updateOverlay(
        gridEnabled: Boolean,
        gridStepX: Float,
        gridStepY: Float,
        gridR: Float,
        gridG: Float,
        gridB: Float,
        gridA: Float,
        rulerEnabled: Boolean,
        rulerX: Float,
        rulerY: Float,
        rulerR: Float,
        rulerG: Float,
        rulerB: Float,
        rulerA: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        pickerEnabled: Boolean,
        highlightLeft: Float,
        highlightTop: Float,
        highlightRight: Float,
        highlightBottom: Float,
        contentZoom: Float = 1f,
        contentPanX: Float = 0f,
        contentPanY: Float = 0f,
    ) {
        GpuMirrorJni.updatePresenterOverlay(
            presenterId,
            gridEnabled, gridStepX, gridStepY, gridR, gridG, gridB, gridA,
            rulerEnabled, rulerX, rulerY, rulerR, rulerG, rulerB, rulerA,
            sourceWidth, sourceHeight,
            pickerEnabled,
            highlightLeft, highlightTop, highlightRight, highlightBottom,
            contentZoom, contentPanX, contentPanY,
        )
    }

    fun updatePickerPoint(normalizedX: Float?, normalizedY: Float?) {
        GpuMirrorJni.updatePresenterPickerPoint(presenterId, normalizedX, normalizedY)
    }

    val decoderId: Long get() = pipeline.decoderId

    /** Re-shows a warm presenter after virtual-desktop switches or Live tab remounts. */
    fun warmResume(host: Canvas) {
        if (attachedHost !== host) {
            attach(host, fillHost)
            return
        }
        visibleRequested = true
        visibleApplied = null
        lastGeometryKey = null
        GpuMirrorJni.resumeAfterDesktopSwitch()
        applyVisible(true)
        invalidateGeometry()
        updateGeometry(host)
        repaint()
    }

    override fun close() {
        warmDetach()
        GpuMirrorHostRegistry.forgetPresenter(this)
        GpuMirrorJni.destroyPresenter(presenterId)
    }

    private fun applyGeometry(component: Component) {
        if (!component.isDisplayable) return
        val applied = runCatching {
            val loc = component.locationOnScreen
            val scale = component.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            val hostW = component.width.coerceAtLeast(1)
            val hostH = component.height.coerceAtLeast(1)
            val videoW = contentWidth
            val videoH = contentHeight
            val x: Int
            val y: Int
            val drawW: Int
            val drawH: Int
            if (fillHost) {
                x = loc.x
                y = loc.y
                drawW = hostW
                drawH = hostH
            } else if (videoW > 0 && videoH > 0) {
                val fit = minOf(hostW.toDouble() / videoW, hostH.toDouble() / videoH)
                drawW = (videoW * fit).toInt().coerceAtLeast(1)
                drawH = (videoH * fit).toInt().coerceAtLeast(1)
                x = loc.x + (hostW - drawW) / 2
                y = loc.y + (hostH - drawH) / 2
            } else {
                x = loc.x
                y = loc.y
                drawW = hostW
                drawH = hostH
            }
            val parentWindowNumber = SwingUtilities.getWindowAncestor(component)?.nsWindowNumber() ?: 0
            val key = "$x,$y,$drawW,$drawH,$scale,$parentWindowNumber"
            if (key != lastGeometryKey) {
                lastGeometryKey = key
                GpuMirrorJni.updatePresenterGeometry(presenterId, x, y, drawW, drawH, scale, parentWindowNumber)
            }
            true
        }.getOrDefault(false)
        // Mark visible once geometry has been attempted. apply_presenter_frame re-fronts a hidden
        // window on its own, so repeating an unchanged visibility here is pure overhead.
        applyVisible(visibleRequested)
        if (!applied) {
            GpuMirrorJni.repaintPresenter(presenterId)
        }
    }

    /** Forces the next geometry pass (e.g. after focus, to re-resolve AppKit parenting). */
    fun invalidateGeometry() {
        lastGeometryKey = null
    }

    /** Re-resolves parent window + geometry against the currently attached host. */
    fun refreshGeometry() {
        val host = attachedHost ?: return
        invalidateGeometry()
        updateGeometry(host)
    }
}
