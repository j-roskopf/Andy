package app.andy.desktop.service.mirror

import app.andy.service.MirrorFrame
import app.andy.desktop.nsWindowNumber
import java.awt.Canvas
import java.awt.Component
import java.awt.Window
import java.io.File
import javax.swing.SwingUtilities
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads the in-process VideoToolbox/Metal bridge for macOS Live mirroring.
 *
 * Compose Desktop cannot reliably composite a JAWT-attached CAMetalLayer, so the product GPU
 * path presents into a borderless mouse-transparent AppKit surface that letterboxes over the
 * Live Canvas. Legacy CPU presentation stays available as fallback.
 */
internal object NativeMirrorJni {
    private val loadResult: Result<Unit> by lazy(::loadLibrary)

    fun ensureLoaded(): Result<Unit> = loadResult
    @Volatile private var metalInlineOverlayOpen = false
    @Volatile private var contentWidth = 0
    @Volatile private var contentHeight = 0
    @Volatile private var geometryHost: Component? = null
    @Volatile private var geometryUpdateScheduled = false
    private var lastGeometryKey: String? = null

    fun isAvailable(): Boolean = loadResult.isSuccess

    /** True when this process can open the inline Metal presenter over the Live Canvas. */
    fun isEmbeddedPresentationSupported(): Boolean = loadResult.isSuccess

    fun embeddedPresentationFailureReason(): String =
        "No packaged VideoToolbox/Metal native mirror bridge is available for this desktop platform"

    /**
     * Opens Metal in a borderless surface that tracks the aspect-fitted video rect inside
     * [host]. Input continues to hit the Canvas underneath because the surface ignores mouse
     * events.
     */
    fun openMetalInlineOverlay(host: Component): Boolean {
        if (!loadResult.isSuccess) return false
        val opened = runCatching(::nativeOpenMetalInlineOverlay).getOrDefault(false)
        metalInlineOverlayOpen = opened
        lastGeometryKey = null
        if (opened) {
            runCatching { nativeSetMetalInlineOverlayVisible(true) }
            updateMetalLayerGeometry(host)
            repaintLatestFrame()
        }
        return opened
    }

    /** True while the borderless inline Metal surface owns pixels over the Live Canvas. */
    fun isMetalInlineOverlayOpen(): Boolean = metalInlineOverlayOpen

    /**
     * Hides or shows the borderless Metal overlay without destroying VideoToolbox. Used when
     * Compose dialogs need to appear above Live (heavyweight AppKit always wins otherwise).
     */
    fun setInlineOverlayVisible(visible: Boolean) {
        if (!loadResult.isSuccess || !metalInlineOverlayOpen) return
        runCatching { nativeSetMetalInlineOverlayVisible(visible) }
        if (visible) {
            lastGeometryKey = null
            NativeMirrorHostRegistry.current()?.let(::updateMetalLayerGeometry)
        }
    }

    /** Re-submits the last decoded iOS/VideoToolbox frame after Metal geometry changes. */
    fun repaintLatestFrame() {
        if (!loadResult.isSuccess || !metalInlineOverlayOpen) return
        runCatching(::nativeRepaintLatestFrame)
    }

    /**
     * Samples the latest decoded VideoToolbox frame as ARGB for bug capture. Returns null when
     * Metal has not presented a frame yet.
     */
    fun copyLatestFrameArgb(): MirrorFrame? {
        if (!loadResult.isSuccess) return null
        return runCatching {
            val size = IntArray(2)
            val pixels = nativeCopyLatestFrameArgb(size) ?: return@runCatching null
            val width = size[0]
            val height = size[1]
            if (width <= 0 || height <= 0 || pixels.size < width * height) return@runCatching null
            MirrorFrame(width, height, pixels)
        }.getOrNull()
    }

    /** Tears down AppKit presentation unconditionally (mirror disconnect). */
    fun destroyPresentation() {
        if (loadResult.isSuccess) runCatching(::nativeDestroyRenderer)
        metalInlineOverlayOpen = false
        contentWidth = 0
        contentHeight = 0
        geometryHost = null
        geometryUpdateScheduled = false
        lastGeometryKey = null
    }

    /**
     * Declares the decoded video size so Metal letterboxes inside the Canvas the same way the
     * CPU painter does. Without this, Metal fills the whole host and stretches.
     */
    fun setPresentationContentSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (contentWidth == width && contentHeight == height) return
        contentWidth = width
        contentHeight = height
        NativeMirrorHostRegistry.current()?.let(::updateMetalLayerGeometry)
    }

    /**
     * Keeps the inline Metal surface over the aspect-fitted video rectangle inside [component].
     *
     * Geometry is coalesced onto a single EDT pass: window-resize callbacks fire rapidly, and
     * pushing each one synchronously into AppKit freezes the Live UI.
     */
    fun updateMetalLayerGeometry(component: Component) {
        if (!loadResult.isSuccess || !component.isDisplayable || !metalInlineOverlayOpen) return
        // The overlay is shared across Live + pop-out hosts. Only the active host may position it;
        // otherwise the main window keeps stealing Metal back and pop-outs stay black.
        val active = NativeMirrorHostRegistry.current()
        if (active != null && active !== component) return
        geometryHost = component
        if (geometryUpdateScheduled) return
        geometryUpdateScheduled = true
        javax.swing.SwingUtilities.invokeLater {
            geometryUpdateScheduled = false
            val host = geometryHost ?: return@invokeLater
            applyMetalLayerGeometry(host)
        }
    }

    private fun applyMetalLayerGeometry(component: Component) {
        if (!loadResult.isSuccess || !component.isDisplayable || !metalInlineOverlayOpen) return
        runCatching {
            if (!component.isShowing) return
            val loc = component.locationOnScreen
            val scale = component.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            val hostW = component.width.coerceAtLeast(1)
            val hostH = component.height.coerceAtLeast(1)
            val videoW = contentWidth
            val videoH = contentHeight
            val fillHost = (component as? Canvas)?.let(NativeMirrorHostRegistry::fillsHost) == true
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
            if (key == lastGeometryKey) return
            lastGeometryKey = key
            nativeUpdateMetalInlineOverlay(x, y, drawW, drawH, scale, parentWindowNumber)
        }
    }

    /** True only after Metal and a VideoToolbox session both report hardware-backed readiness. */
    fun isHardwareReady(): Boolean =
        loadResult.isSuccess && runCatching(::nativeIsHardwareReady).getOrDefault(false)

    fun removeMetalLayer(component: Component) {
        if (!loadResult.isSuccess) return
        // Presentation is shared across Live + Compose pop-out canvases. Closing one host must
        // rebind to any remaining canvas instead of destroying the VT/Metal session. Callers must
        // unregister the departing host before this so [otherDisplayable] is accurate.
        if (!metalInlineOverlayOpen) return
        val remaining = (component as? Canvas)?.let(NativeMirrorHostRegistry::otherDisplayable)
            ?: NativeMirrorHostRegistry.current()
        if (remaining != null) {
            updateMetalLayerGeometry(remaining)
            return
        }
        // Destination switches briefly have zero hosts. Keep VideoToolbox/Metal warm so the next
        // Live/Design/Accessibility surface can rebind, but never leave its heavyweight pixels
        // above the destination that replaced Live. DesktopMirrorEngine.disconnect() still tears
        // presentation down when the session is truly released.
        setInlineOverlayVisible(false)
        lastGeometryKey = null
    }

    /** Sends one parser-bounded Annex-B H.264 access unit to the VideoToolbox/Metal renderer. */
    fun consumeH264(packet: ByteArray): Boolean =
        loadResult.isSuccess && packet.isNotEmpty() && runCatching { nativeConsumeH264(packet) }.getOrDefault(false)

    fun framesPresented(): Long =
        if (loadResult.isSuccess) runCatching(::nativeFramesPresented).getOrDefault(0L) else 0L

    fun recordInput() {
        if (loadResult.isSuccess) runCatching(::nativeRecordInput)
    }

    fun recordTransportIngress() {
        if (loadResult.isSuccess) runCatching(::nativeRecordTransportIngress)
    }

    /** Region in decoded-frame coordinates used to turn a host input into a visual latency sample. */
    fun configureLatencyProbe(left: Float, top: Float, width: Float, height: Float) {
        if (loadResult.isSuccess) runCatching { nativeConfigureLatencyProbe(left, top, width, height) }
    }

    fun latencyProbeTransitions(): Long =
        if (loadResult.isSuccess) runCatching(::nativeLatencyProbeTransitions).getOrDefault(0L) else 0L

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
        if (loadResult.isSuccess) {
            runCatching {
                nativeUpdateOverlay(
                    gridEnabled, gridStepX, gridStepY, gridR, gridG, gridB, gridA,
                    rulerEnabled, rulerX, rulerY, rulerR, rulerG, rulerB, rulerA,
                    sourceWidth, sourceHeight,
                    pickerEnabled,
                    highlightLeft, highlightTop, highlightRight, highlightBottom,
                )
            }
        }
    }

    /** Moves the native picker lens without waiting for a Compose recomposition. */
    fun updatePickerPoint(normalizedX: Float?, normalizedY: Float?) {
        if (!loadResult.isSuccess) return
        runCatching {
            nativeUpdatePickerPoint(
                normalizedX ?: 0f,
                normalizedY ?: 0f,
                normalizedX != null && normalizedY != null,
            )
        }
    }

    /** Reads one decoded pixel from the native YUV surface without copying a video frame to JVM. */
    fun inspectPixel(normalizedX: Float, normalizedY: Float): String? {
        if (!loadResult.isSuccess) return null
        val color = runCatching { nativeInspectPixel(normalizedX, normalizedY) }.getOrDefault(-1)
        if (color == -1) return null
        return "#%02X%02X%02X".format((color ushr 16) and 0xff, (color ushr 8) and 0xff, color and 0xff)
    }

    fun p95InputToPresentMillis(): Float? =
        if (!loadResult.isSuccess) null else runCatching(::nativeP95InputToPresentMillis)
            .getOrNull()
            ?.takeIf { it >= 0f }

    fun inputToPresentSamplesMillis(): String? =
        if (!loadResult.isSuccess) null else runCatching(::nativeInputToPresentSamplesMillis).getOrNull()

    /** Local H.264-byte-arrival to Metal-present latency, for queue diagnostics. */
    fun p95PacketToPresentMillis(): Float? =
        if (!loadResult.isSuccess) null else runCatching(::nativeP95PacketToPresentMillis)
            .getOrNull()
            ?.takeIf { it >= 0f }

    /** Local ADB-tunnel byte arrival to Metal-present latency, including Annex-B assembly. */
    fun p95TransportToPresentMillis(): Float? =
        if (!loadResult.isSuccess) null else runCatching(::nativeP95TransportToPresentMillis)
            .getOrNull()
            ?.takeIf { it >= 0f }

    private fun loadLibrary() = runCatching {
        val resourcePath = resourcePath() ?: error("No native mirror bridge is packaged for this platform")
        val target = File(System.getProperty("user.home"), ".andy/mirror/$resourcePath")
        target.parentFile.mkdirs()
        javaClass.classLoader.getResourceAsStream(resourcePath)?.use {
            Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Missing packaged native mirror bridge: $resourcePath")
        System.load(target.absolutePath)
    }

    internal fun resourcePath(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        if (!os.contains("mac") && !os.contains("darwin")) return null
        return when (osArch.lowercase()) {
            "aarch64", "arm64" -> "andy-mirror/macos-arm64/andy-mirror-jni.dylib"
            "x86_64", "amd64" -> "andy-mirror/macos-x86_64/andy-mirror-jni.dylib"
            else -> null
        }
    }

    private external fun nativeOpenMetalInlineOverlay(): Boolean
    private external fun nativeUpdateMetalInlineOverlay(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Double,
        parentWindowNumber: Int,
    )
    private external fun nativeSetMetalInlineOverlayVisible(visible: Boolean)
    private external fun nativeRepaintLatestFrame()
    private external fun nativeCopyLatestFrameArgb(outSize: IntArray): IntArray?
    private external fun nativeIsHardwareReady(): Boolean
    private external fun nativeDestroyRenderer()
    private external fun nativeConsumeH264(packet: ByteArray): Boolean
    private external fun nativeFramesPresented(): Long
    private external fun nativeRecordInput()
    private external fun nativeRecordTransportIngress()
    private external fun nativeConfigureLatencyProbe(left: Float, top: Float, width: Float, height: Float)
    private external fun nativeLatencyProbeTransitions(): Long
    private external fun nativeUpdateOverlay(
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
    )
    private external fun nativeUpdatePickerPoint(normalizedX: Float, normalizedY: Float, visible: Boolean)
    private external fun nativeInspectPixel(normalizedX: Float, normalizedY: Float): Int
    private external fun nativeP95InputToPresentMillis(): Float
    private external fun nativeInputToPresentSamplesMillis(): String
    private external fun nativeP95PacketToPresentMillis(): Float
    private external fun nativeP95TransportToPresentMillis(): Float
}
