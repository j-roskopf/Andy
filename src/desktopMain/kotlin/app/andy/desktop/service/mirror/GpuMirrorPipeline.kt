package app.andy.desktop.service.mirror

import app.andy.desktop.MirrorPresentationGuard
import app.andy.desktop.nsWindowNumber
import java.awt.Canvas
import java.awt.Component
import java.awt.Window
import javax.swing.SwingUtilities

/** One VideoToolbox decode stream; may fan out to multiple [GpuMirrorPresenter] instances. */
internal class GpuMirrorPipeline private constructor(
    val decoderId: Long,
) : AutoCloseable {
    private var closed = false

    fun createPresenter(host: Canvas? = null): GpuMirrorPresenter? {
        if (closed) return null
        if (host != null) {
            GpuMirrorHostRegistry.presenterFor(host)?.let { return it }
        }
        GpuMirrorHostRegistry.pruneOrphanedPresenters(decoderId)
        val presenterId = GpuMirrorJni.createPresenter(decoderId)
        if (presenterId == 0L) return null
        return GpuMirrorPresenter(this, presenterId)
    }

    fun consumeH264(packet: ByteArray): Boolean = !closed && GpuMirrorJni.consumeH264(decoderId, packet)

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

    fun bindIosCapture() {
        if (!closed) GpuMirrorJni.bindIosDecoder(decoderId)
    }

    fun unbindIosCapture() {
        // Only release the global iOS routing slot if *this* decoder still owns it. Closing an
        // unrelated (e.g. Android) pipeline must not blank a live iOS mirror bound to another decoder.
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
internal class GpuMirrorPresenter internal constructor(
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

    fun attach(host: Canvas, fillHost: Boolean): Boolean {
        if (attachedHost === host && this.fillHost == fillHost) {
            updateGeometry(host)
            return true
        }
        detach()
        this.fillHost = fillHost
        if (!GpuMirrorJni.openPresenterOverlay(presenterId)) return false
        GpuMirrorJni.setPresenterFillHost(presenterId, fillHost)
        GpuMirrorJni.setPresenterVisible(presenterId, false)
        attachedHost = host
        GpuMirrorHostRegistry.registerPresenter(host, this)
        lastGeometryKey = null
        val finalizeAttach = {
            commitGeometry(host)
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
        val host = attachedHost
        attachedHost = null
        // Only clear the registry slot when we still own it. Replacing a presenter on the same
        // Canvas closes the previous one after the new registration; that close must not wipe
        // the replacement out of GpuMirrorHostRegistry.
        if (host != null && GpuMirrorHostRegistry.presenterFor(host) === this) {
            GpuMirrorHostRegistry.unregisterPresenter(host)
        } else {
            GpuMirrorHostRegistry.forgetPresenter(this)
        }
        GpuMirrorJni.setPresenterVisible(presenterId, false)
    }

    fun setVisible(visible: Boolean) {
        visibleRequested = visible
        GpuMirrorJni.setPresenterVisible(presenterId, visible)
        // Do not refresh geometry here. updateGeometry → setFrame flashes the black Canvas
        // under the mouse-transparent Metal overlay (every click looked like a black blink).
        // Occlusion resume and attach/resize paths call updateGeometry explicitly.
    }

    /** Re-front the Metal overlay without resizing (safe on mouse press / focus). */
    fun bringToFront() {
        visibleRequested = true
        GpuMirrorJni.setPresenterVisible(presenterId, true)
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
    ) {
        GpuMirrorJni.updatePresenterOverlay(
            presenterId,
            gridEnabled, gridStepX, gridStepY, gridR, gridG, gridB, gridA,
            rulerEnabled, rulerX, rulerY, rulerR, rulerG, rulerB, rulerA,
            sourceWidth, sourceHeight,
            pickerEnabled,
            highlightLeft, highlightTop, highlightRight, highlightBottom,
        )
    }

    fun updatePickerPoint(normalizedX: Float?, normalizedY: Float?) {
        GpuMirrorJni.updatePresenterPickerPoint(presenterId, normalizedX, normalizedY)
    }

    internal val decoderId: Long get() = pipeline.decoderId

    override fun close() {
        detach()
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
        // Mark visible once geometry has been attempted. setPresenterVisible no longer
        // orderFronts when already showing, so this is cheap and does not flash.
        GpuMirrorJni.setPresenterVisible(presenterId, visibleRequested)
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
