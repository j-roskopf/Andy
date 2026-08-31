package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.andy.domain.effectiveHighlightBounds
import app.andy.domain.highlightContentUv
import app.andy.LocalSuppressHeavyweightSurfaces
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import app.andy.desktop.MirrorPresentationGuard
import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.GpuMirrorJni
import app.andy.desktop.service.mirror.GpuMirrorPresenter
import app.andy.desktop.service.mirror.GpuMirrorSessions
import app.andy.desktop.service.mirror.LinuxMirrorDesktopVisibilityEffect
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import java.awt.AlphaComposite
import java.awt.Point
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.awt.geom.Ellipse2D
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Composable
actual fun MirrorVideoSurface(
    frame: MirrorFrame?,
    modifier: Modifier,
    onInput: (MirrorInput) -> Unit,
    onHoverColor: (String) -> Unit,
    passThroughInput: Boolean,
    onPickerClick: (String) -> Unit,
    onDevicePointClick: (Int, Int) -> Unit,
    onRulerResize: (Float, Float) -> Unit,
    onContentPan: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
    occluded: Boolean,
    deferNativePresentation: Boolean,
    nativePresentation: Boolean,
    nativePresentationFillHost: Boolean,
    gpuMirrorStreamKey: Any?,
) {
    if (isScreenshotRenderer()) {
        ScreenshotMirrorSurface(frame, modifier, overlay)
        return
    }
    val suppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    // SwingPanel punches a Skia clear-hole above Compose popups; hiding the child is not
    // enough (the host still eclipses chrome DropdownMenus), so tear the interop down.
    key(nativePresentation, nativePresentationFillHost, gpuMirrorStreamKey) {
        val panel = remember {
            MirrorPanel(
                hostsNativePresentation = nativePresentation,
                fillNativePresentationHost = nativePresentationFillHost,
                gpuMirrorStreamKey = gpuMirrorStreamKey,
            )
        }
        LinuxMirrorDesktopVisibilityEffect(
            enabled = nativePresentation && gpuMirrorStreamKey != null && GpuMirrorJni.isAvailable(),
        )
        Box(modifier.background(Color.Black)) {
            if (!suppressHeavyweight) {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    background = Color.Black,
                    factory = { panel },
                    update = {
                        panel.setFrame(frame)
                        panel.onInput = onInput
                        panel.onHoverColor = onHoverColor
                        panel.passThroughInput = passThroughInput
                        panel.onPickerClick = onPickerClick
                        panel.onDevicePointClick = onDevicePointClick
                        panel.onRulerResize = onRulerResize
                        panel.onContentPan = onContentPan
                        panel.setOverlay(overlay)
                        panel.setOccluded(occluded)
                    },
                )
            }
        }
    }
}

@Composable
actual fun MirrorVideoSurface(
    frames: Flow<MirrorFrame>,
    resetKey: Any?,
    modifier: Modifier,
    onInput: (MirrorInput) -> Unit,
    onHoverColor: (String) -> Unit,
    passThroughInput: Boolean,
    onPickerClick: (String) -> Unit,
    onDevicePointClick: (Int, Int) -> Unit,
    onRulerResize: (Float, Float) -> Unit,
    onContentPan: (Float, Float) -> Unit,
    overlay: MirrorOverlay,
    occluded: Boolean,
    deferNativePresentation: Boolean,
    nativePresentation: Boolean,
    nativePresentationFillHost: Boolean,
    gpuMirrorStreamKey: Any?,
) {
    if (isScreenshotRenderer()) {
        val frame by frames.collectAsState(initial = null)
        ScreenshotMirrorSurface(frame, modifier, overlay)
        return
    }
    val suppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    key(nativePresentation, nativePresentationFillHost, gpuMirrorStreamKey) {
        val panel = remember {
            MirrorPanel(
                hostsNativePresentation = nativePresentation,
                fillNativePresentationHost = nativePresentationFillHost,
                gpuMirrorStreamKey = gpuMirrorStreamKey,
            )
        }
        LinuxMirrorDesktopVisibilityEffect(
            enabled = nativePresentation && gpuMirrorStreamKey != null && GpuMirrorJni.isAvailable(),
        )
        Box(modifier.background(Color.Black)) {
            if (!suppressHeavyweight) {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    background = Color.Black,
                    factory = { panel },
                    update = {
                        panel.onInput = onInput
                        panel.onHoverColor = onHoverColor
                        panel.passThroughInput = passThroughInput
                        panel.onPickerClick = onPickerClick
                        panel.onDevicePointClick = onDevicePointClick
                        panel.onRulerResize = onRulerResize
                        panel.onContentPan = onContentPan
                        panel.setOverlay(overlay)
                        panel.setOccluded(occluded)
                    },
                )
            }
        }
        LaunchedEffect(panel, frames, resetKey) {
            frames.collectLatest { frame ->
                panel.enqueueFrame(frame)
            }
        }
    }
}

private fun isScreenshotRenderer(): Boolean =
    System.getProperty("andy.screenshot.renderer") == "compose"

/** Compose-only mirror for Roborazzi's off-window test scene. */
@Composable
private fun ScreenshotMirrorSurface(frame: MirrorFrame?, modifier: Modifier, overlay: MirrorOverlay) {
    Canvas(modifier.background(Color.Black)) {
        val sourceWidth = frame?.width ?: 270
        val sourceHeight = frame?.height ?: 600
        val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        drawRect(Color(0xff1a2936), topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(width, height))
        drawRect(Color(0xff30485a), topLeft = androidx.compose.ui.geometry.Offset(left + width * .08f, top + height * .12f), size = androidx.compose.ui.geometry.Size(width * .84f, height * .19f))
        drawRect(Color(0xff406b63), topLeft = androidx.compose.ui.geometry.Offset(left + width * .08f, top + height * .38f), size = androidx.compose.ui.geometry.Size(width * .84f, height * .15f))
        drawRect(Color(0xff675c96), topLeft = androidx.compose.ui.geometry.Offset(left + width * .08f, top + height * .60f), size = androidx.compose.ui.geometry.Size(width * .38f, height * .18f))
        drawRect(Color(0xff8a6544), topLeft = androidx.compose.ui.geometry.Offset(left + width * .54f, top + height * .60f), size = androidx.compose.ui.geometry.Size(width * .38f, height * .18f))
        if (overlay.showGrid) {
            val color = if (overlay.gridColor == Color.Transparent) Color.White.copy(alpha = .25f) else overlay.gridColor
            val step = (overlay.gridSize * scale).coerceAtLeast(8f)
            var x = left
            while (x <= left + width) { drawLine(color, androidx.compose.ui.geometry.Offset(x, top), androidx.compose.ui.geometry.Offset(x, top + height), 1f); x += step }
            var y = top
            while (y <= top + height) { drawLine(color, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(left + width, y), 1f); y += step }
        }
    }
}

private class MirrorPanel(
    private val hostsNativePresentation: Boolean = true,
    private val fillNativePresentationHost: Boolean = false,
    private val gpuMirrorStreamKey: Any? = null,
) : java.awt.Canvas() {
    private enum class DragMode { Device, RulerX, RulerY, Inspect, Pan, None }

    private var image: BufferedImage? = null
    private var presentBuffer: BufferedImage? = null
    private var frameNumber: Long = -1
    // Native VideoToolbox frames carry dimensions only: Metal owns their pixels. Keeping this
    // separate from a CPU image prevents the Swing fallback painter from clearing under the
    // Metal presenter with an uninitialized BufferedImage on every native-frame update.
    private var nativeMetadataFrame = false
    private var overlay: MirrorOverlay = MirrorOverlay()
    private var referenceImagePath: String? = null
    private var referenceImage: BufferedImage? = null
    private var referenceImageRequestId = 0L
    private var occluded = false
    var onInput: (MirrorInput) -> Unit = {}
    var onHoverColor: (String) -> Unit = {}
    var onPickerClick: (String) -> Unit = {}
    var onDevicePointClick: (Int, Int) -> Unit = { _, _ -> }
    var onRulerResize: (Float, Float) -> Unit = { _, _ -> }
    var onContentPan: (Float, Float) -> Unit = { _, _ -> }
    var passThroughInput: Boolean = true
    private var pressedPoint: Point? = null
    private var panAnchor: Point? = null
    private var panOriginX = 0f
    private var panOriginY = 0f
    private var pickerPoint: Point? = null
    private var dragMode = DragMode.None
    private var lastDeviceMoveSentAtNanos = 0L
    private val frameLock = Any()
    private var pendingFrame: MirrorFrame? = null
    private var frameDispatchPending = false
    private var gpuPresenter: GpuMirrorPresenter? = null
    private var resumeGpuPresentationAttempts = 0
    private var ancestorWindow: Window? = null
    private val ancestorWindowListener = object : WindowAdapter() {
        override fun windowActivated(e: WindowEvent) {
            if (!occluded && hostsNativePresentation) resumeGpuPresentationAfterShow()
        }

        override fun windowStateChanged(e: WindowEvent) {
            if (!occluded && hostsNativePresentation) updatePresentationGeometry()
        }
    }

    private fun prefersGpuHub(): Boolean =
        hostsNativePresentation && gpuMirrorStreamKey != null && GpuMirrorJni.isAvailable()

    private fun usesGpuHub(): Boolean =
        prefersGpuHub() && GpuMirrorSessions.get(gpuMirrorStreamKey!!) != null

    private fun invalidateGpuPresenter() {
        gpuPresenter = null
    }

    private fun ensureGpuPresenter(): GpuMirrorPresenter? {
        if (!prefersGpuHub()) return null
        val key = gpuMirrorStreamKey ?: return null
        val pipeline = GpuMirrorSessions.get(key) ?: run {
            invalidateGpuPresenter()
            return null
        }
        val cached = gpuPresenter
        if (cached != null &&
            cached.decoderId == pipeline.decoderId &&
            GpuMirrorHostRegistry.presenterFor(this) === cached
        ) {
            return cached
        }
        GpuMirrorHostRegistry.presenterFor(this)?.let { existing ->
            if (existing.decoderId == pipeline.decoderId) {
                gpuPresenter = existing
                return existing
            }
            existing.close()
        }
        GpuMirrorHostRegistry.unattachedPresenterForDecoder(pipeline.decoderId)?.let { orphan ->
            gpuPresenter = orphan
            return orphan
        }
        cached?.takeIf { it.decoderId == pipeline.decoderId }?.let { orphan ->
            gpuPresenter = orphan
            return orphan
        }
        cached?.close()
        invalidateGpuPresenter()
        return pipeline.createPresenter(this)?.also { gpuPresenter = it }
    }

    private fun attachGpuPresentationIfReady() {
        if (MirrorPresentationGuard.suppressingGeometry || !prefersGpuHub()) return
        // Boot attach before the Canvas has a real size leaves a stuck/invisible overlay;
        // tab remount works because size is already valid by then.
        if (!isShowing || width <= 0 || height <= 0) return
        val presenter = ensureGpuPresenter() ?: return
        if (presenter.isAttachedTo(this)) {
            presenter.updateGeometry(this)
            presenter.repaint()
            return
        }
        presenter.attach(this, fillNativePresentationHost)
    }

    private fun resumeGpuPresentationAfterShow() {
        if (!prefersGpuHub() || occluded || !hostsNativePresentation || !isDisplayable) return
        val resume = Runnable {
            // Retry until the hub session exists and the host has real bounds — on first
            // Live open the session often arrives after addNotify.
            if (!isDisplayable || !isShowing || width <= 0 || height <= 0 || !usesGpuHub()) {
                if (resumeGpuPresentationAttempts++ < 40) {
                    SwingUtilities.invokeLater(this::resumeGpuPresentationAfterShow)
                }
                return@Runnable
            }
            resumeGpuPresentationAttempts = 0
            attachGpuPresentationIfReady()
            ensureGpuPresenter()?.warmResume(this)
            GpuMirrorJni.resumeAfterDesktopSwitch()
        }
        if (SwingUtilities.isEventDispatchThread()) resume.run() else SwingUtilities.invokeLater(resume)
    }

    private var presentationGeometryPending = false

    private fun updatePresentationGeometry() {
        if (MirrorPresentationGuard.suppressingGeometry || occluded || !hostsNativePresentation) return
        if (presentationGeometryPending) return
        presentationGeometryPending = true
        SwingUtilities.invokeLater {
            presentationGeometryPending = false
            if (!isDisplayable || occluded || !hostsNativePresentation) return@invokeLater
            if (usesGpuHub()) {
                gpuPresenter?.updateGeometry(this)
            } else {
                NativeMirrorJni.updateMetalLayerGeometry(this)
            }
        }
    }

    init {
        background = java.awt.Color.BLACK
        preferredSize = Dimension(240, 520)
        // System-driven repaints clear heavyweight Canvas peers. CPU frames present
        // explicitly after each decode instead.
        ignoreRepaint = true
        // Let the panel own keyboard focus so physical keystrokes reach the device,
        // and keep Tab/Enter for ourselves instead of moving focus within Swing.
        isFocusable = true
        focusTraversalKeysEnabled = false
        addKeyListener(object : KeyAdapter() {
            override fun keyTyped(event: KeyEvent) {
                if (!passThroughInput) return
                // Shortcut chords (Cmd/Ctrl+V paste, etc.) must not inject the bare letter.
                if (event.isMetaDown || event.isControlDown) return
                val char = event.keyChar
                // Printable characters are forwarded as text; control keys (Enter,
                // Backspace, Tab, Esc, Delete) are handled in keyPressed as key events.
                if (char == KeyEvent.CHAR_UNDEFINED || char.isISOControl()) return
                onInput(MirrorInput.Text(char.toString()))
            }

            override fun keyPressed(event: KeyEvent) {
                if (!passThroughInput) return
                if (isPasteShortcut(event)) {
                    readSystemClipboardText()?.let { onInput(MirrorInput.Text(it)) }
                    event.consume()
                    return
                }
                val androidKeyCode = androidKeyCodeFor(event.keyCode) ?: return
                onInput(MirrorInput.Key(androidKeyCode))
                event.consume()
            }
        })
        val listener = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (MIRROR_DEBUG) {
                    System.err.println(
                        "MIRROR_MOUSE_PRESS: pt=${event.point} passThrough=$passThroughInput" +
                            " image=${image?.width}x${image?.height} mapped=${mapPoint(event.point)}" +
                            " size=${width}x$height metadata=$nativeMetadataFrame",
                    )
                }
                pressedPoint = event.point
                val rulerMode = if (mapPoint(event.point) != null) rulerDragMode(event.point) else DragMode.None
                // Focus/geometry churn buries the Metal child window under the black Canvas,
                // which makes ruler/grid lines vanish mid-drag. Leave Metal alone while Design
                // guides are active or while panning a zoomed frame.
                val willPan = overlay.contentZoom > 1.01f &&
                    rulerMode == DragMode.None &&
                    overlay.pickerColor == null
                val designGuidesActive =
                    overlay.showRuler || overlay.showGrid || overlay.pickerColor != null
                val keepMetalFront = designGuidesActive || willPan
                if (!keepMetalFront && !isFocusOwner) {
                    requestFocusInWindow()
                }
                // When zoomed, left-drag pans the visible crop; use the trackpad/wheel too.
                // Ruler/picker still take priority when active.
                if (willPan) {
                    dragMode = DragMode.Pan
                    panAnchor = event.point
                    panOriginX = overlay.contentPanX
                    panOriginY = overlay.contentPanY
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    return
                }
                mapPoint(event.point)?.let { point ->
                    dragMode = rulerMode
                    when {
                        dragMode == DragMode.RulerX || dragMode == DragMode.RulerY -> updateRulerDrag(event.point)
                        overlay.pickerColor != null -> {
                            dragMode = DragMode.Inspect
                            updateHoverColor(event.point)?.let(onPickerClick)
                        }
                        passThroughInput -> {
                            dragMode = DragMode.Device
                            lastDeviceMoveSentAtNanos = 0L
                            onInput(MirrorInput.Touch(MirrorTouchAction.Down, point.x, point.y))
                        }
                        else -> {
                            dragMode = DragMode.Inspect
                            val source = toSourcePoint(point)
                            onDevicePointClick(source.x, source.y)
                        }
                    }
                }
            }

            override fun mouseDragged(event: MouseEvent) {
                if (overlay.pickerColor != null) {
                    updateHoverColor(event.point)
                }
                when (dragMode) {
                    DragMode.RulerX, DragMode.RulerY -> updateRulerDrag(event.point)
                    DragMode.Pan -> {
                        val anchor = panAnchor ?: return
                        val rect = fittedContentRect() ?: return
                        val zoom = overlay.contentZoom.coerceAtLeast(1f)
                        val panRange = (1f - 1f / zoom).coerceAtLeast(0.0001f)
                        val dx = (event.point.x - anchor.x).toFloat() / rect.width.toFloat()
                        val dy = (event.point.y - anchor.y).toFloat() / rect.height.toFloat()
                        // Dragging the image should move content with the cursor.
                        val nextX = (panOriginX - dx / panRange).coerceIn(0f, 1f)
                        val nextY = (panOriginY - dy / panRange).coerceIn(0f, 1f)
                        overlay = overlay.copy(contentPanX = nextX, contentPanY = nextY)
                        updateNativeOverlay(overlay)
                        onContentPan(nextX, nextY)
                    }
                    // Clamp so a fling that leaves the image edge still carries its
                    // final velocity to the device instead of being dropped.
                    DragMode.Device -> mapPoint(event.point, clamp = true)?.let(::sendDeviceMove)
                    else -> Unit
                }
            }

            override fun mouseReleased(event: MouseEvent) {
                // Always release a device touch, even when the mouse is let go outside
                // the image bounds. Skipping the Up here strands a finger "down" on the
                // emulator (it uses NEVER_EXPIRE), which wedges every later tap.
                if (dragMode == DragMode.Device) {
                    mapPoint(event.point, clamp = true)?.let { point ->
                        onInput(MirrorInput.Touch(MirrorTouchAction.Up, point.x, point.y))
                    }
                }
                pressedPoint = null
                panAnchor = null
                lastDeviceMoveSentAtNanos = 0L
                dragMode = DragMode.None
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseMoved(event: MouseEvent) {
                if (overlay.pickerColor != null) {
                    updateHoverColor(event.point)
                }
                cursor = when (rulerDragMode(event.point)) {
                    DragMode.RulerX -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                    DragMode.RulerY -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
                    else -> Cursor.getDefaultCursor()
                }
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                if (overlay.contentZoom <= 1.01f) return
                val step = (0.06f / overlay.contentZoom).coerceIn(0.01f, 0.12f)
                val rotation = event.preciseWheelRotation.toFloat()
                val nextX: Float
                val nextY: Float
                if (event.isShiftDown) {
                    nextX = (overlay.contentPanX + rotation * step).coerceIn(0f, 1f)
                    nextY = overlay.contentPanY
                } else {
                    nextX = overlay.contentPanX
                    nextY = (overlay.contentPanY + rotation * step).coerceIn(0f, 1f)
                }
                overlay = overlay.copy(contentPanX = nextX, contentPanY = nextY)
                updateNativeOverlay(overlay)
                onContentPan(nextX, nextY)
                event.consume()
            }

            override fun mouseExited(event: MouseEvent) {
                pickerPoint = null
                if (overlay.pickerColor != null) {
                    ensureGpuPresenter()?.updatePickerPoint(null, null)
                        ?: NativeMirrorJni.updatePickerPoint(null, null)
                }
                if (!nativeMetadataFrame) presentCpuFrame()
            }
        }
        addMouseListener(listener)
        addMouseMotionListener(listener)
        addMouseWheelListener(listener)
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                if (occluded || !hostsNativePresentation) return
                if (usesGpuHub()) {
                    ensureGpuPresenter()?.repaint()
                } else {
                    updatePresentationGeometry()
                }
            }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (!occluded && hostsNativePresentation) updatePresentationGeometry()
                if (!nativeMetadataFrame) presentCpuFrame()
            }

            override fun componentMoved(event: ComponentEvent) {
                if (!occluded && hostsNativePresentation) updatePresentationGeometry()
            }

            override fun componentShown(event: ComponentEvent) {
                if (!occluded && hostsNativePresentation) {
                    SwingUtilities.invokeLater { resumeGpuPresentationAfterShow() }
                }
                if (!nativeMetadataFrame) presentCpuFrame()
            }
        })
        addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorResized(event: HierarchyEvent) {
                if (!occluded && hostsNativePresentation) updatePresentationGeometry()
            }

            override fun ancestorMoved(event: HierarchyEvent) {
                if (!occluded && hostsNativePresentation) updatePresentationGeometry()
            }
        })
        addHierarchyListener(HierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L &&
                isShowing && !occluded && hostsNativePresentation
            ) {
                resumeGpuPresentationAfterShow()
            }
        })
    }

    override fun addNotify() {
        super.addNotify()
        SwingUtilities.getWindowAncestor(this)?.let { window ->
            ancestorWindow?.takeIf { it !== window }?.removeWindowListener(ancestorWindowListener)
            ancestorWindow = window
            window.addWindowListener(ancestorWindowListener)
        }
        if (hostsNativePresentation) {
            if (prefersGpuHub()) {
                SwingUtilities.invokeLater { resumeGpuPresentationAfterShow() }
            } else {
                NativeMirrorHostRegistry.markHostsMetalPresentation(
                    this,
                    hostsMetal = true,
                    fillHost = fillNativePresentationHost,
                )
                NativeMirrorHostRegistry.register(this)
            }
        }
    }

    override fun removeNotify() {
        // Teardown runs before Canvas loses its heavyweight peer. Unregister first so a sibling
        // Live/pop-out host can reclaim the Metal presenter instead of destroying the session.
        presentationGeometryPending = false
        ancestorWindow?.removeWindowListener(ancestorWindowListener)
        ancestorWindow = null
        if (hostsNativePresentation) {
            if (prefersGpuHub()) {
                gpuPresenter?.detach()
                gpuPresenter = null
            } else {
                NativeMirrorHostRegistry.unregister(this)
                NativeMirrorJni.removeMetalLayer(this)
            }
        }
        super.removeNotify()
    }

    /**
     * Compose dialogs cannot paint above heavyweight SwingPanel / Metal. Hide both without
     * tearing down VideoToolbox so Live resumes immediately after dismiss.
     */
    fun setOccluded(next: Boolean) {
        if (occluded == next) return
        occluded = next
        isVisible = !next
        if (hostsNativePresentation) {
            if (prefersGpuHub()) {
                val presenter = ensureGpuPresenter()
                presenter?.setVisible(!next)
                if (!next) {
                    presenter?.updateGeometry(this)
                    presenter?.repaint()
                }
            } else {
                NativeMirrorJni.setInlineOverlayVisible(!next)
                if (!next) updatePresentationGeometry()
            }
        }
        if (!next && !nativeMetadataFrame) presentCpuFrame()
    }

    // Maps AWT key codes for non-text keys to Android key codes (KeyEvent.KEYCODE_*).
    private fun androidKeyCodeFor(awtKeyCode: Int): Int? = when (awtKeyCode) {
        KeyEvent.VK_ENTER -> 66
        KeyEvent.VK_BACK_SPACE -> 67
        KeyEvent.VK_DELETE -> 112
        KeyEvent.VK_TAB -> 61
        KeyEvent.VK_ESCAPE -> 111
        KeyEvent.VK_UP -> 19
        KeyEvent.VK_DOWN -> 20
        KeyEvent.VK_LEFT -> 21
        KeyEvent.VK_RIGHT -> 22
        KeyEvent.VK_HOME -> 122
        KeyEvent.VK_END -> 123
        KeyEvent.VK_PAGE_UP -> 92
        KeyEvent.VK_PAGE_DOWN -> 93
        else -> null
    }

    private fun isPasteShortcut(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.VK_V) return false
        return event.isMetaDown || event.isControlDown
    }

    private fun readSystemClipboardText(): String? =
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            (clipboard.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun sendDeviceMove(point: DevicePoint) {
        val now = System.nanoTime()
        if (now - lastDeviceMoveSentAtNanos < DEVICE_MOVE_MIN_INTERVAL_NANOS) return
        lastDeviceMoveSentAtNanos = now
        onInput(MirrorInput.Touch(MirrorTouchAction.Move, point.x, point.y))
    }

    fun setFrame(frame: MirrorFrame?) {
        if (frame == null) return
        val hasCpuPixels = frame.argb.size >= frame.width * frame.height
        if (!hasCpuPixels) {
            val sameMetadata = frame.frameNumber == frameNumber &&
                image?.width == frame.width &&
                image?.height == frame.height
            if (sameMetadata) return
        }
        frameNumber = frame.frameNumber
        nativeMetadataFrame = !hasCpuPixels
        val sizeChanged = image?.width != frame.width || image?.height != frame.height
        val buffered = image?.takeIf { it.width == frame.width && it.height == frame.height }
            ?: BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).also {
                image = it
            }
        if (hasCpuPixels) {
            val pixels = (buffered.raster.dataBuffer as DataBufferInt).data
            frame.argb.copyInto(pixels, endIndex = frame.width * frame.height)
            presentCpuFrame()
        } else {
            attachGpuPresentationIfReady()
            if (usesGpuHub()) {
                gpuMirrorStreamKey?.let { key ->
                    GpuMirrorSessions.get(key)?.setContentSize(frame.width, frame.height)
                    ensureGpuPresenter()?.setContentSize(frame.width, frame.height)
                }
                if (gpuPresenter?.isAttachedTo(this) != true) {
                    SwingUtilities.invokeLater { resumeGpuPresentationAfterShow() }
                }
            } else {
                NativeMirrorJni.setPresentationContentSize(frame.width, frame.height)
            }
            if (sizeChanged && hostsNativePresentation) updatePresentationGeometry()
        }
    }

    fun enqueueFrame(frame: MirrorFrame) {
        synchronized(frameLock) {
            pendingFrame = frame
            if (frameDispatchPending) return
            frameDispatchPending = true
        }
        SwingUtilities.invokeLater {
            val next = synchronized(frameLock) {
                frameDispatchPending = false
                pendingFrame.also { pendingFrame = null }
            }
            setFrame(next)
        }
    }

    fun setOverlay(next: MirrorOverlay) {
        if (overlay == next) return
        updateNativeOverlay(next)
        if (referenceImagePath != next.referenceImagePath || overlay.referenceImageKey != next.referenceImageKey) {
            referenceImagePath = next.referenceImagePath
            val requestId = ++referenceImageRequestId
            val path = next.referenceImagePath
            referenceImage = null
            if (path != null) {
                CompletableFuture.supplyAsync {
                    runCatching { ImageIO.read(File(path)) }.getOrNull()
                }.thenAccept { loadedImage ->
                    SwingUtilities.invokeLater {
                        if (referenceImageRequestId == requestId && referenceImagePath == path) {
                            referenceImage = loadedImage
                            if (!nativeMetadataFrame) presentCpuFrame()
                        }
                    }
                }
            }
        }
        overlay = next
        // The Metal layer draws its overlay itself. Asking this heavyweight Canvas to repaint
        // while it is overlaid can make AWT clear the host surface to its black background
        // after Metal has presented a drawable.
        if (!nativeMetadataFrame) presentCpuFrame()
    }

    /** Mirrors geometry controls into Metal; the Java paint path still owns CPU fallback/tests. */
    private fun updateNativeOverlay(next: MirrorOverlay) {
        val frameWidth = image?.width?.takeIf { it > 1 }
        val frameHeight = image?.height?.takeIf { it > 1 }
        val sourceWidth = (frameWidth ?: next.sourceWidth ?: image?.width ?: 1).coerceAtLeast(1)
        val sourceHeight = (frameHeight ?: next.sourceHeight ?: image?.height ?: 1).coerceAtLeast(1)
        val gridColor = if (next.gridColor == Color.Transparent) {
            Color.White.copy(alpha = .28f)
        } else {
            next.gridColor.copy(alpha = .28f)
        }
        val rulerColor = next.rulerColor.copy(alpha = .95f)
        val highlightUv = highlightContentUv(
            next.highlightBounds,
            next.boundsDisplayWidth,
            next.boundsDisplayHeight,
            sourceWidth,
            sourceHeight,
        )
        val gpu = ensureGpuPresenter()
        if (gpu != null && usesGpuHub()) {
            gpu.updateOverlay(
                gridEnabled = next.showGrid && next.gridSize >= 2f,
                gridStepX = (next.gridSize / sourceWidth).coerceIn(0f, 1f),
                gridStepY = (next.gridSize / sourceHeight).coerceIn(0f, 1f),
                gridR = gridColor.red,
                gridG = gridColor.green,
                gridB = gridColor.blue,
                gridA = gridColor.alpha,
                rulerEnabled = next.showRuler,
                rulerX = (next.rulerX / sourceWidth).coerceIn(0f, 1f),
                rulerY = (next.rulerY / sourceHeight).coerceIn(0f, 1f),
                rulerR = rulerColor.red,
                rulerG = rulerColor.green,
                rulerB = rulerColor.blue,
                rulerA = rulerColor.alpha,
                sourceWidth = sourceWidth.toFloat(),
                sourceHeight = sourceHeight.toFloat(),
                pickerEnabled = next.pickerColor != null,
                highlightLeft = highlightUv?.get(0)?.coerceIn(0f, 1f) ?: 1f,
                highlightTop = highlightUv?.get(1)?.coerceIn(0f, 1f) ?: 1f,
                highlightRight = highlightUv?.get(2)?.coerceIn(0f, 1f) ?: 0f,
                highlightBottom = highlightUv?.get(3)?.coerceIn(0f, 1f) ?: 0f,
                contentZoom = next.contentZoom.coerceAtLeast(1f),
                contentPanX = next.contentPanX.coerceIn(0f, 1f),
                contentPanY = next.contentPanY.coerceIn(0f, 1f),
            )
            return
        }
        NativeMirrorJni.updateOverlay(
            gridEnabled = next.showGrid && next.gridSize >= 2f,
            gridStepX = (next.gridSize / sourceWidth).coerceIn(0f, 1f),
            gridStepY = (next.gridSize / sourceHeight).coerceIn(0f, 1f),
            gridR = gridColor.red,
            gridG = gridColor.green,
            gridB = gridColor.blue,
            gridA = gridColor.alpha,
            rulerEnabled = next.showRuler,
            rulerX = (next.rulerX / sourceWidth).coerceIn(0f, 1f),
            rulerY = (next.rulerY / sourceHeight).coerceIn(0f, 1f),
            rulerR = rulerColor.red,
            rulerG = rulerColor.green,
            rulerB = rulerColor.blue,
            rulerA = rulerColor.alpha,
            sourceWidth = sourceWidth.toFloat(),
            sourceHeight = sourceHeight.toFloat(),
            pickerEnabled = next.pickerColor != null,
            highlightLeft = highlightUv?.get(0)?.coerceIn(0f, 1f) ?: 1f,
            highlightTop = highlightUv?.get(1)?.coerceIn(0f, 1f) ?: 1f,
            highlightRight = highlightUv?.get(2)?.coerceIn(0f, 1f) ?: 0f,
            highlightBottom = highlightUv?.get(3)?.coerceIn(0f, 1f) ?: 0f,
        )
    }

    override fun update(graphics: Graphics) {
        // ignoreRepaint=true; keep this as a no-clear guard if anything still routes here.
        if (nativeMetadataFrame) return
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        if (nativeMetadataFrame) return
        renderCpuFrame(graphics)
    }

    /**
     * Presents the latest CPU frame through an offscreen buffer so the black letterbox fill and
     * scaled image hit the screen in one blit. Painting those steps directly to a heavyweight
     * Canvas flashes between frames on macOS.
     */
    private fun presentCpuFrame() {
        if (nativeMetadataFrame || occluded || !isDisplayable || width <= 0 || height <= 0) return
        val g = graphics ?: return
        try {
            renderCpuFrame(g)
        } finally {
            g.dispose()
        }
    }

    private fun renderCpuFrame(graphics: Graphics) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val buffer = presentBuffer?.takeIf { it.width == w && it.height == h }
            ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { presentBuffer = it }
        val g2 = buffer.createGraphics()
        try {
            g2.color = java.awt.Color.BLACK
            g2.fillRect(0, 0, w, h)
            val frameImage = image
            if (frameImage != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val rect = fittedRect(frameImage)
                // The bitmap crop is in frameImage's own pixel space; annotations (grid/ruler/
                // highlight/gesture) are reported in overlay.sourceWidth/Height device-pixel space.
                // Those can differ (a downscaled preview frame vs. the real device resolution), so
                // scale contentZoom/Pan into each space separately rather than sharing one window.
                val imageWindow = contentWindow(frameImage.width, frameImage.height)
                g2.drawImage(
                    frameImage,
                    rect.x, rect.y, rect.x + rect.width, rect.y + rect.height,
                    imageWindow.x.roundToInt(), imageWindow.y.roundToInt(),
                    (imageWindow.x + imageWindow.width).roundToInt(), (imageWindow.y + imageWindow.height).roundToInt(),
                    null,
                )
                val annotationWindow = contentWindow(
                    overlay.sourceWidth ?: frameImage.width,
                    overlay.sourceHeight ?: frameImage.height,
                )
                paintOverlay(g2, frameImage, rect, annotationWindow)
            }
        } finally {
            g2.dispose()
        }
        graphics.drawImage(buffer, 0, 0, null)
    }

    // When clamp is false, points outside the fitted image return null (used for
    // hit-testing hover/press). When clamp is true, off-image points are pinned to the
    // nearest edge so an in-progress drag/release still produces a valid device point.
    private fun mapPoint(point: Point, clamp: Boolean = false): DevicePoint? {
        val rect = fittedContentRect() ?: return null
        val (contentWidth, contentHeight) = mirrorContentSize() ?: return null
        val localX = point.x - rect.x
        val localY = point.y - rect.y
        if (!clamp && (localX < 0 || localY < 0 || localX > rect.width || localY > rect.height)) return null
        // Must be floating-point division — Int/Int truncates to 0 for every pixel inside the rect.
        val hostU = (localX.toFloat() / rect.width.toFloat()).coerceIn(0f, 1f)
        val hostV = (localY.toFloat() / rect.height.toFloat()).coerceIn(0f, 1f)
        val zoom = overlay.contentZoom.coerceAtLeast(1f)
        val originX = overlay.contentPanX.coerceIn(0f, 1f) * (1f - 1f / zoom)
        val originY = overlay.contentPanY.coerceIn(0f, 1f) * (1f - 1f / zoom)
        val contentU = (originX + hostU / zoom).coerceIn(0f, 1f)
        val contentV = (originY + hostV / zoom).coerceIn(0f, 1f)
        return DevicePoint(
            x = (contentU * contentWidth).roundToInt().coerceIn(0, contentWidth - 1),
            y = (contentV * contentHeight).roundToInt().coerceIn(0, contentHeight - 1),
        )
    }

    /** Visible source-pixel window the CPU path must draw/overlay into [rect] — identity when unzoomed. */
    private data class ContentWindow(val x: Float, val y: Float, val width: Float, val height: Float)

    /** Mirrors [mapPoint]'s zoom math so the CPU-drawn frame and its overlay agree with hit-testing. */
    private fun contentWindow(sourceWidth: Int, sourceHeight: Int): ContentWindow {
        val zoom = overlay.contentZoom.coerceAtLeast(1f)
        if (zoom <= 1.01f) return ContentWindow(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
        val windowWidth = sourceWidth / zoom
        val windowHeight = sourceHeight / zoom
        val originX = overlay.contentPanX.coerceIn(0f, 1f) * (sourceWidth - windowWidth)
        val originY = overlay.contentPanY.coerceIn(0f, 1f) * (sourceHeight - windowHeight)
        return ContentWindow(originX, originY, windowWidth, windowHeight)
    }

    /** Authoritative mirror size for hit-testing — matches the scrcpy/Metal stream from [setFrame]. */
    private fun mirrorContentSize(): Pair<Int, Int>? {
        val frameImage = image
        if (frameImage != null && frameImage.width > 1 && frameImage.height > 1) {
            return frameImage.width to frameImage.height
        }
        val overlayWidth = overlay.sourceWidth?.takeIf { it > 1 }
        val overlayHeight = overlay.sourceHeight?.takeIf { it > 1 }
        if (overlayWidth != null && overlayHeight != null) {
            return overlayWidth to overlayHeight
        }
        return null
    }

    private fun fittedContentRect(): DrawRect? {
        if (width <= 0 || height <= 0) return null
        val (contentWidth, contentHeight) = mirrorContentSize() ?: return null
        val scale = minOf(width.toDouble() / contentWidth, height.toDouble() / contentHeight)
        val drawWidth = (contentWidth * scale).toInt()
        val drawHeight = (contentHeight * scale).toInt()
        return DrawRect((width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, scale)
    }

    private fun fittedRect(frameImage: BufferedImage): DrawRect {
        val scale = minOf(width.toDouble() / frameImage.width, height.toDouble() / frameImage.height)
        val drawWidth = (frameImage.width * scale).toInt()
        val drawHeight = (frameImage.height * scale).toInt()
        return DrawRect((width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, scale)
    }

    private fun paintOverlay(g2: Graphics2D, frameImage: BufferedImage, rect: DrawRect, window: ContentWindow) {
        referenceImage?.let { image ->
            val imageGraphics = g2.create() as Graphics2D
            try {
                imageGraphics.clipRect(rect.x, rect.y, rect.width, rect.height)
                imageGraphics.composite = AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER,
                    overlay.referenceImageOpacity.coerceIn(0f, 1f),
                )
                imageGraphics.drawImage(image, rect.x, rect.y, rect.x + rect.width, rect.y + rect.height, 0, 0, image.width, image.height, null)
            } finally {
                imageGraphics.dispose()
            }
        }
        // Grid/ruler/highlight are expressed in the same source-pixel space as [window], so a
        // world coordinate maps to screen via (world - window.origin) * rect.size / window.size —
        // identical to [window]'s crop when unzoomed.
        if (overlay.showGrid && overlay.gridSize >= 2f) {
            val color = overlay.gridColor.toAwtColor(alphaOverride = 0.28f)
            g2.color = color
            g2.stroke = BasicStroke(1f)
            val gridSize = overlay.gridSize
            val stepX = gridSize * rect.width / window.width
            if (stepX > 0f) {
                var worldX = kotlin.math.ceil(window.x / gridSize) * gridSize
                var x = rect.x + (worldX - window.x) * rect.width / window.width
                while (x <= rect.x + rect.width) {
                    g2.drawLine(x.roundToInt(), rect.y, x.roundToInt(), rect.y + rect.height)
                    worldX += gridSize
                    x += stepX
                }
            }
            val stepY = gridSize * rect.height / window.height
            if (stepY > 0f) {
                var worldY = kotlin.math.ceil(window.y / gridSize) * gridSize
                var y = rect.y + (worldY - window.y) * rect.height / window.height
                while (y <= rect.y + rect.height) {
                    g2.drawLine(rect.x, y.roundToInt(), rect.x + rect.width, y.roundToInt())
                    worldY += gridSize
                    y += stepY
                }
            }
        }
        if (overlay.showRuler) {
            val color = overlay.rulerColor.toAwtColor(alphaOverride = 0.95f)
            g2.color = color
            g2.stroke = BasicStroke(1.5f)
            val sourceWidth = overlay.sourceWidth ?: frameImage.width
            val sourceHeight = overlay.sourceHeight ?: frameImage.height
            val xPx = overlay.rulerX.coerceIn(0f, sourceWidth.toFloat())
            val yPx = overlay.rulerY.coerceIn(0f, sourceHeight.toFloat())
            val drawX = rect.x + ((xPx - window.x) * rect.width / window.width).roundToInt()
            val drawY = rect.y + ((yPx - window.y) * rect.height / window.height).roundToInt()
            g2.drawLine(drawX, rect.y, drawX, rect.y + rect.height)
            g2.drawLine(rect.x, drawY, rect.x + rect.width, drawY)
            g2.fillOval(drawX - 4, rect.y + rect.height / 2 - 4, 8, 8)
            g2.fillOval(rect.x + rect.width / 2 - 4, drawY - 4, 8, 8)
            drawPill(g2, drawX + 8, rect.y + 8, xPx.roundToInt().toString())
            drawPill(g2, (drawX - 70).coerceAtLeast(rect.x + 4), rect.y + rect.height - 24, (sourceWidth - xPx).roundToInt().toString())
            drawPill(g2, rect.x + 8, drawY + 8, yPx.roundToInt().toString())
            drawPill(g2, rect.x + rect.width - 74, (drawY - 24).coerceAtLeast(rect.y + 4), (sourceHeight - yPx).roundToInt().toString())
        }
        effectiveHighlightBounds(
            overlay.highlightBounds,
            overlay.boundsDisplayWidth,
            overlay.boundsDisplayHeight,
            overlay.sourceWidth ?: frameImage.width,
            overlay.sourceHeight ?: frameImage.height,
        )?.let { bounds ->
            val scaleX = rect.width / window.width
            val scaleY = rect.height / window.height
            val x = rect.x + ((bounds[0] - window.x) * scaleX).roundToInt()
            val y = rect.y + ((bounds[1] - window.y) * scaleY).roundToInt()
            val w = ((bounds[2] - bounds[0]) * scaleX).roundToInt().coerceAtLeast(2)
            val h = ((bounds[3] - bounds[1]) * scaleY).roundToInt().coerceAtLeast(2)
            g2.color = java.awt.Color(216, 111, 74, 90)
            g2.fillRect(x, y, w, h)
            g2.color = java.awt.Color(216, 111, 74, 240)
            g2.stroke = BasicStroke(2f)
            g2.drawRect(x, y, w, h)
        }
        overlay.gesture?.let { gesture ->
            paintGesture(g2, frameImage, rect, window, gesture)
        }
        overlay.pickerColor?.let { color ->
            val radius = 48
            val point = pickerPoint?.takeIf { it.x in rect.x..(rect.x + rect.width) && it.y in rect.y..(rect.y + rect.height) }
            val cx = point?.x ?: (rect.x + rect.width * 7 / 10)
            val cy = point?.y ?: (rect.y + rect.height / 3)
            val mapped = point?.let { mapPoint(it) }
            if (mapped != null) {
                drawMagnifier(g2, frameImage, cx, cy, radius, mapped)
            } else {
                g2.color = color.toAwtColor(alphaOverride = 0.38f)
                g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2)
            }
            g2.color = java.awt.Color(216, 111, 74)
            g2.stroke = BasicStroke(2f)
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2)
            g2.drawLine(cx - 7, cy, cx + 7, cy)
            g2.drawLine(cx, cy - 7, cx, cy + 7)
            drawPill(g2, cx - 24, cy + radius + 8, overlay.pickerHex ?: color.toHex())
        }
    }

    private fun paintGesture(g2: Graphics2D, frameImage: BufferedImage, rect: DrawRect, window: ContentWindow, gesture: MirrorGestureOverlay) {
        val sourceWidth = (overlay.sourceWidth ?: frameImage.width).coerceAtLeast(1)
        val sourceHeight = (overlay.sourceHeight ?: frameImage.height).coerceAtLeast(1)
        fun toScreen(x: Int, y: Int) = Point(
            rect.x + ((x.coerceIn(0, sourceWidth) - window.x) * rect.width / window.width).roundToInt(),
            rect.y + ((y.coerceIn(0, sourceHeight) - window.y) * rect.height / window.height).roundToInt(),
        )
        val alpha = (1f - gesture.fadeProgress).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val start = toScreen(gesture.startX, gesture.startY)
        val target = gesture.endX?.let { endX -> toScreen(endX, gesture.endY ?: gesture.startY) }
        val current = target?.let { end ->
            Point(
                (start.x + (end.x - start.x) * gesture.swipeProgress.coerceIn(0f, 1f)).roundToInt(),
                (start.y + (end.y - start.y) * gesture.swipeProgress.coerceIn(0f, 1f)).roundToInt(),
            )
        } ?: start
        val lineColor = java.awt.Color(216, 111, 74, (220 * alpha).roundToInt())
        val haloColor = java.awt.Color(216, 111, 74, (64 * alpha).roundToInt())
        g2.color = haloColor
        g2.fillOval(current.x - 20, current.y - 20, 40, 40)
        if (target != null) {
            g2.color = lineColor
            g2.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawLine(start.x, start.y, current.x, current.y)
            g2.fillOval(start.x - 5, start.y - 5, 10, 10)
            val dx = current.x - start.x
            val dy = current.y - start.y
            val length = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
            if (length > 1.0) {
                val unitX = dx / length
                val unitY = dy / length
                val baseX = current.x - unitX * 12
                val baseY = current.y - unitY * 12
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(current.x, current.y, (baseX - unitY * 5).roundToInt(), (baseY + unitX * 5).roundToInt())
                g2.drawLine(current.x, current.y, (baseX + unitY * 5).roundToInt(), (baseY - unitX * 5).roundToInt())
            }
        }
        g2.color = lineColor
        g2.stroke = BasicStroke(2f)
        g2.drawOval(current.x - 14, current.y - 14, 28, 28)
        g2.color = java.awt.Color(245, 245, 245, (230 * alpha).roundToInt())
        g2.fillOval(current.x - 4, current.y - 4, 8, 8)
        g2.color = lineColor
        g2.stroke = BasicStroke(1f)
        g2.drawLine(current.x - 18, current.y, current.x + 18, current.y)
        g2.drawLine(current.x, current.y - 18, current.x, current.y + 18)
    }

    private fun drawMagnifier(g2: Graphics2D, frameImage: BufferedImage, cx: Int, cy: Int, radius: Int, point: DevicePoint) {
        val zoom = 5
        val sourceRadius = (radius / zoom).coerceAtLeast(1)
        val sourceLeft = (point.x - sourceRadius).coerceAtLeast(0)
        val sourceTop = (point.y - sourceRadius).coerceAtLeast(0)
        val sourceRight = (point.x + sourceRadius + 1).coerceAtMost(frameImage.width)
        val sourceBottom = (point.y + sourceRadius + 1).coerceAtMost(frameImage.height)
        val destLeft = cx - (point.x - sourceLeft) * zoom - zoom / 2
        val destTop = cy - (point.y - sourceTop) * zoom - zoom / 2
        val destRight = destLeft + (sourceRight - sourceLeft) * zoom
        val destBottom = destTop + (sourceBottom - sourceTop) * zoom
        val lens = Ellipse2D.Float(
            (cx - radius).toFloat(),
            (cy - radius).toFloat(),
            (radius * 2).toFloat(),
            (radius * 2).toFloat(),
        )
        val lensGraphics = g2.create() as Graphics2D
        try {
            lensGraphics.clip = lens
            lensGraphics.color = java.awt.Color(8, 8, 8)
            lensGraphics.fill(lens)
            lensGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            lensGraphics.drawImage(
                frameImage,
                destLeft,
                destTop,
                destRight,
                destBottom,
                sourceLeft,
                sourceTop,
                sourceRight,
                sourceBottom,
                null,
            )
            lensGraphics.color = java.awt.Color(0, 0, 0, 58)
            lensGraphics.stroke = BasicStroke(1f)
            for (x in cx downTo cx - radius step zoom) {
                lensGraphics.drawLine(x, cy - radius, x, cy + radius)
            }
            for (x in cx + zoom..(cx + radius) step zoom) {
                lensGraphics.drawLine(x, cy - radius, x, cy + radius)
            }
            for (y in cy downTo cy - radius step zoom) {
                lensGraphics.drawLine(cx - radius, y, cx + radius, y)
            }
            for (y in cy + zoom..(cy + radius) step zoom) {
                lensGraphics.drawLine(cx - radius, y, cx + radius, y)
            }
        } finally {
            lensGraphics.dispose()
        }
    }

    private fun updateHoverColor(point: Point): String? {
        val frameImage = image
        val (contentWidth, contentHeight) = mirrorContentSize() ?: return null
        val mapped = mapPoint(point) ?: run {
            pickerPoint = null
            val gpu = ensureGpuPresenter()
            if (overlay.pickerColor != null) {
                gpu?.updatePickerPoint(null, null)
                    ?: NativeMirrorJni.updatePickerPoint(null, null)
            }
            if (!nativeMetadataFrame) presentCpuFrame()
            return null
        }
        pickerPoint = point
        val normalizedX = mapped.x.toFloat() / contentWidth
        val normalizedY = mapped.y.toFloat() / contentHeight
        val gpu = ensureGpuPresenter()?.takeIf { usesGpuHub() }
        if (overlay.pickerColor != null) {
            gpu?.updatePickerPoint(normalizedX, normalizedY)
                ?: NativeMirrorJni.updatePickerPoint(normalizedX, normalizedY)
        }
        val nativeHex = gpu?.let { GpuMirrorJni.inspectPixel(it.decoderId, normalizedX, normalizedY) }
            ?: NativeMirrorJni.inspectPixel(normalizedX, normalizedY)
        val hex = nativeHex ?: run {
            if (
                frameImage == null ||
                nativeMetadataFrame ||
                mapped.x !in 0 until frameImage.width ||
                mapped.y !in 0 until frameImage.height
            ) {
                return null
            }
            val rgb = frameImage.getRGB(mapped.x, mapped.y)
            val color = java.awt.Color(rgb, true)
            "#%02X%02X%02X".format(color.red, color.green, color.blue)
        }
        onHoverColor(hex)
        if (!nativeMetadataFrame) presentCpuFrame()
        return hex
    }

    private fun rulerDragMode(point: Point): DragMode {
        if (!overlay.showRuler) return DragMode.None
        val rect = fittedContentRect() ?: return DragMode.None
        val (contentWidth, contentHeight) = mirrorContentSize() ?: return DragMode.None
        val sourceWidth = (overlay.sourceWidth ?: contentWidth).coerceAtLeast(1)
        val sourceHeight = (overlay.sourceHeight ?: contentHeight).coerceAtLeast(1)
        val zoom = overlay.contentZoom.coerceAtLeast(1f)
        val originX = overlay.contentPanX.coerceIn(0f, 1f) * (1f - 1f / zoom)
        val originY = overlay.contentPanY.coerceIn(0f, 1f) * (1f - 1f / zoom)
        val contentU = overlay.rulerX.coerceIn(0f, sourceWidth.toFloat()) / sourceWidth.toFloat()
        val contentV = overlay.rulerY.coerceIn(0f, sourceHeight.toFloat()) / sourceHeight.toFloat()
        val hostU = ((contentU - originX) * zoom).coerceIn(0f, 1f)
        val hostV = ((contentV - originY) * zoom).coerceIn(0f, 1f)
        val drawX = rect.x + (hostU * rect.width).roundToInt()
        val drawY = rect.y + (hostV * rect.height).roundToInt()
        val nearVerticalLine = point.y in rect.y..(rect.y + rect.height) && kotlin.math.abs(point.x - drawX) <= 14
        val nearHorizontalLine = point.x in rect.x..(rect.x + rect.width) && kotlin.math.abs(point.y - drawY) <= 14
        return when {
            nearVerticalLine -> DragMode.RulerX
            nearHorizontalLine -> DragMode.RulerY
            else -> DragMode.None
        }
    }

    private fun updateRulerDrag(point: Point) {
        val mapped = mapPoint(point, clamp = true) ?: return
        val source = toSourcePoint(mapped)
        val sourceWidth = (overlay.sourceWidth ?: mirrorContentSize()?.first ?: return)
            .coerceAtLeast(1)
            .toFloat()
        val sourceHeight = (overlay.sourceHeight ?: mirrorContentSize()?.second ?: return)
            .coerceAtLeast(1)
            .toFloat()
        val nextX = if (dragMode == DragMode.RulerX) {
            source.x.toFloat().coerceIn(0f, sourceWidth - 1f)
        } else {
            overlay.rulerX.coerceIn(0f, sourceWidth - 1f)
        }
        val nextY = if (dragMode == DragMode.RulerY) {
            source.y.toFloat().coerceIn(0f, sourceHeight - 1f)
        } else {
            overlay.rulerY.coerceIn(0f, sourceHeight - 1f)
        }
        // Update Metal immediately so guides stay visible during the Compose round-trip.
        overlay = overlay.copy(rulerX = nextX, rulerY = nextY)
        updateNativeOverlay(overlay)
        if (!nativeMetadataFrame) presentCpuFrame()
        onRulerResize(nextX, nextY)
    }

    private fun toSourcePoint(point: DevicePoint): DevicePoint {
        val frameImage = image ?: return point
        val sourceWidth = overlay.sourceWidth ?: frameImage.width
        val sourceHeight = overlay.sourceHeight ?: frameImage.height
        return DevicePoint(
            x = (point.x.toDouble() / frameImage.width * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1),
            y = (point.y.toDouble() / frameImage.height * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1),
        )
    }

    private fun drawPill(g2: Graphics2D, x: Int, y: Int, text: String) {
        val width = maxOf(20, text.length * 7 + 10)
        g2.color = java.awt.Color(216, 111, 74)
        g2.fillRoundRect(x, y, width, 16, 8, 8)
        g2.color = java.awt.Color.WHITE
        g2.font = g2.font.deriveFont(10f)
        g2.drawString(text, x + 5, y + 12)
    }

    private fun parseBounds(bounds: String?): List<Int>? {
        if (bounds.isNullOrBlank()) return null
        val values = Regex("""\d+""").findAll(bounds).map { it.value.toInt() }.toList()
        return values.takeIf { it.size == 4 }
    }

    private fun Color.toAwtColor(alphaOverride: Float? = null): java.awt.Color {
        return java.awt.Color(
            red.coerceIn(0f, 1f),
            green.coerceIn(0f, 1f),
            blue.coerceIn(0f, 1f),
            (alphaOverride ?: alpha).coerceIn(0f, 1f),
        )
    }

    private fun Color.toHex(): String {
        val r = (red * 255).toInt().coerceIn(0, 255)
        val g = (green * 255).toInt().coerceIn(0, 255)
        val b = (blue * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private data class DevicePoint(val x: Int, val y: Int)
    private data class DrawRect(val x: Int, val y: Int, val width: Int, val height: Int, val scale: Double)

    companion object {
        private const val DEVICE_MOVE_MIN_INTERVAL_NANOS = 8_000_000L

        /** Set ANDY_MIRROR_DEBUG=1 to trace mouse delivery into the mirror surface. */
        private val MIRROR_DEBUG = System.getenv("ANDY_MIRROR_DEBUG") == "1"
    }
}
