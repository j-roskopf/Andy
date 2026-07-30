package app.andy.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.MirrorOverlay
import app.andy.MirrorVideoSurface
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.hardware_bug
import app.andy.andy.generated.resources.hardware_capture
import app.andy.andy.generated.resources.hardware_clipboard
import app.andy.andy.generated.resources.hardware_pop_out
import app.andy.andy.generated.resources.hardware_power
import app.andy.andy.generated.resources.hardware_record
import app.andy.andy.generated.resources.hardware_rotate
import app.andy.andy.generated.resources.hardware_volume_down
import app.andy.andy.generated.resources.hardware_volume_up
import app.andy.domain.scalePointFromStreamToDisplay
import app.andy.model.AndroidDevice
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.ui.actions.DockPlacement
import app.andy.ui.actions.TerminalDockToggleRow
import app.andy.ui.components.noiseGridOverlay
import app.andy.ui.components.OutlinedButton
import app.andy.ui.controls.FoldableDisplayProfile
import app.andy.ui.controls.FoldablePosture
import app.andy.ui.controls.foldablePostureForAngle
import app.andy.ui.controls.sizeForPosture
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.shell.LocalWindowResizing
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt
internal data class MirrorSourceSize(val width: Int, val height: Int)

internal data class FoldableStreamContext(
    val profile: FoldableDisplayProfile,
    val hingeAngle: Float,
)

/**
 * Size used for the Live host aspect ratio. Prefer an explicit [captureHint] (foldable
 * outer/inner after open/close) so the host can resize while the mirror restarts; then the
 * active stream frame; then the mirror session (GPU paths often omit CPU frames); then
 * device screen size; then a tall-phone default.
 */
internal fun liveMirrorSourceSize(
    device: AndroidDevice?,
    frame: MirrorFrame?,
    session: MirrorSession? = null,
    captureHint: MirrorSourceSize? = null,
    foldableProfile: FoldableDisplayProfile? = null,
    foldableHingeAngle: Float = 180f,
): MirrorSourceSize = liveMirrorLayoutSize(
    device = device,
    frame = frame,
    session = session,
    captureHint = captureHint,
    foldableProfile = foldableProfile,
    foldableHingeAngle = foldableHingeAngle,
    allowDeviceScreenFallback = true,
)

/**
 * Mirror box aspect during foldable open/close. Uses [captureHint] and AVD profile when the
 * live stream still reflects the previous posture so the host does not briefly letterbox open
 * content into a closed box (or vice versa).
 */
internal fun liveMirrorLayoutSize(
    device: AndroidDevice?,
    frame: MirrorFrame?,
    session: MirrorSession? = null,
    captureHint: MirrorSourceSize? = null,
    foldableProfile: FoldableDisplayProfile? = null,
    foldableHingeAngle: Float = 180f,
    allowDeviceScreenFallback: Boolean = false,
): MirrorSourceSize {
    if (captureHint != null && captureHint.width > 1 && captureHint.height > 1) {
        return captureHint
    }
    val foldable = foldableProfile?.let { FoldableStreamContext(it, foldableHingeAngle) }
    val stream = resolveLiveMirrorSourceSize(
        device = device,
        frame = frame,
        session = session,
        captureHint = null,
        foldable = foldable,
        allowDeviceScreenFallback = allowDeviceScreenFallback,
    )
    val profile = foldableProfile ?: return stream
    val posture = foldablePostureForAngle(foldableHingeAngle)
    if (!foldableStreamMatchesPosture(stream, posture, profile)) {
        val (width, height) = profile.sizeForPosture(posture)
        return MirrorSourceSize(width, height)
    }
    return stream
}

/**
 * Scrcpy stream dimensions for touch mapping. Never uses physical `wm size` from
 * [AndroidDevice.screenSize] — that lags fold/unfold and ignores max_size scaling.
 */
internal fun liveMirrorStreamSize(
    device: AndroidDevice?,
    frame: MirrorFrame?,
    session: MirrorSession? = null,
    foldableProfile: FoldableDisplayProfile? = null,
    foldableHingeAngle: Float = 180f,
): MirrorSourceSize = resolveLiveMirrorSourceSize(
    device = device,
    frame = frame,
    session = session,
    captureHint = null,
    foldable = foldableProfile?.let { FoldableStreamContext(it, foldableHingeAngle) },
    allowDeviceScreenFallback = false,
)

/**
 * Pixel size of the decoded mirror texture for overlays and hit-testing. Prefer the live
 * [frame] over scrcpy session metadata — session dimensions can lag or disagree with the
 * texture after the stream stabilizes, which misaligns inspector bounds overlays.
 */
internal fun liveMirrorFrameSize(frame: MirrorFrame?, session: MirrorSession? = null): MirrorSourceSize {
    frame?.takeIf { it.width > 1 && it.height > 1 }?.let { return MirrorSourceSize(it.width, it.height) }
    session?.takeIf { it.width > 1 && it.height > 1 }?.let { return MirrorSourceSize(it.width, it.height) }
    return MirrorSourceSize(1, 1)
}

private fun resolveLiveMirrorSourceSize(
    device: AndroidDevice?,
    frame: MirrorFrame?,
    session: MirrorSession?,
    captureHint: MirrorSourceSize?,
    foldable: FoldableStreamContext?,
    allowDeviceScreenFallback: Boolean,
): MirrorSourceSize {
    if (captureHint != null && captureHint.width > 1 && captureHint.height > 1) {
        return captureHint
    }
    val frameSize = frame
        ?.takeIf { it.width > 1 && it.height > 1 }
        ?.let { MirrorSourceSize(it.width, it.height) }
    val sessionSize = session
        ?.takeIf { it.width > 1 && it.height > 1 }
        ?.let { MirrorSourceSize(it.width, it.height) }
    if (frameSize != null && sessionSize != null && frameSize != sessionSize) {
        return pickMismatchedStreamSize(
            frameSize = frameSize,
            sessionSize = sessionSize,
            sessionReady = session?.readyForPresentation == true,
            foldable = foldable,
        )
    }
    if (sessionSize != null) return sessionSize
    if (frameSize != null) return frameSize
    if (allowDeviceScreenFallback) {
        val raw = device?.screenSize
        if (raw != null) {
            val width = raw.substringBefore('x').toIntOrNull()
            val height = raw.substringAfter('x').toIntOrNull()
            if (width != null && height != null && width > 1 && height > 1 && 'x' in raw) {
                return MirrorSourceSize(width, height)
            }
        }
    }
    return MirrorSourceSize(1080, 2400)
}

private fun pickMismatchedStreamSize(
    frameSize: MirrorSourceSize,
    sessionSize: MirrorSourceSize,
    sessionReady: Boolean,
    foldable: FoldableStreamContext?,
): MirrorSourceSize {
    val frameAspect = frameSize.width.toFloat() / frameSize.height
    val sessionAspect = sessionSize.width.toFloat() / sessionSize.height
    if (kotlin.math.abs(frameAspect - sessionAspect) < 0.06f) {
        return if (sessionReady) sessionSize else frameSize
    }
    foldable?.let { context ->
        val posture = foldablePostureForAngle(context.hingeAngle)
        val (expectedWidth, expectedHeight) = context.profile.sizeForPosture(posture)
        val expectedAspect = expectedWidth.toFloat() / expectedHeight
        val frameMatches = kotlin.math.abs(frameAspect - expectedAspect) < 0.06f
        val sessionMatches = kotlin.math.abs(sessionAspect - expectedAspect) < 0.06f
        if (frameMatches && !sessionMatches) return frameSize
        if (sessionMatches && !frameMatches) return sessionSize
    }
    return frameSize
}

/** True when [stream] matches the aspect ratio of [posture] on this AVD (scaled stream is OK). */
internal fun foldableStreamMatchesPosture(
    stream: MirrorSourceSize,
    posture: FoldablePosture,
    profile: FoldableDisplayProfile,
): Boolean {
    val (expectedWidth, expectedHeight) = profile.sizeForPosture(posture)
    val streamAspect = stream.width.toFloat() / stream.height.toFloat()
    val expectedAspect = expectedWidth.toFloat() / expectedHeight.toFloat()
    return kotlin.math.abs(streamAspect - expectedAspect) < 0.06f
}

/** Minimum pane width to show the hardware toolbar plus a height-fitted mirror. */
internal fun liveDevicePaneFittedWidth(
    maxPaneHeight: Dp,
    device: AndroidDevice?,
    frame: MirrorFrame?,
    showHardwareControls: Boolean,
    showDeviceHeader: Boolean,
    showChromeControls: Boolean,
    showContainerChrome: Boolean = true,
    session: MirrorSession? = null,
    captureHint: MirrorSourceSize? = null,
    foldableProfile: FoldableDisplayProfile? = null,
    foldableHingeAngle: Float = 180f,
): Dp {
    val source = liveMirrorSourceSize(
        device = device,
        frame = frame,
        session = session,
        captureHint = captureHint,
        foldableProfile = foldableProfile,
        foldableHingeAngle = foldableHingeAngle,
    )
    val aspect = source.width.toFloat() / source.height.toFloat()
    val horizontalChrome = if (showContainerChrome) AndySpace.Space5 * 2 else 0.dp
    val verticalChrome = if (showContainerChrome) AndySpace.Space5 * 2 else 0.dp
    val toolbarWidth = if (showHardwareControls) 68.dp else 0.dp
    val toolbarGap = if (showHardwareControls) 10.dp else 0.dp
    val headerBlock = if (showDeviceHeader) 42.dp else 0.dp
    val navHeight = if (showChromeControls) 60.dp else 0.dp
    val mirrorViewportHeight = (maxPaneHeight - verticalChrome - headerBlock - navHeight).coerceAtLeast(1.dp)
    val mirrorWidth = mirrorViewportHeight * aspect
    val fittedToMirror = toolbarWidth + toolbarGap + horizontalChrome + mirrorWidth
    // Tall phones fit the mirror into a narrow column; keep enough room for stream
    // chips + Pop out so the header does not feel crushed on first load.
    val headerFloor = if (showDeviceHeader) 560.dp else 0.dp
    return maxOf(fittedToMirror, headerFloor)
}

@Composable
internal fun LiveDevicePane(
    serial: String?,
    device: AndroidDevice?,
    displayName: String? = device?.displayName,
    frame: MirrorFrame?,
    frameFlow: Flow<MirrorFrame>? = null,
    mirrorStatus: String,
    mirrorSession: MirrorSession? = null,
    mirrorTelemetry: String = "",
    connectResult: String,
    modifier: Modifier = Modifier,
    highlightBounds: String? = null,
    boundsDisplayWidth: Int? = null,
    boundsDisplayHeight: Int? = null,
    showRuler: Boolean = false,
    rulerWidth: Float = 0.5f,
    rulerHeight: Float = 0.5f,
    rulerX: Float = 0.5f,
    rulerY: Float = 0.5f,
    gridSize: Float? = null,
    gridColor: Color = Color.White.copy(alpha = 0.14f),
    pickerColor: Color? = null,
    pickerHex: String? = null,
    referenceImagePath: String? = null,
    referenceImageKey: Long = 0L,
    referenceImageOpacity: Float = 0.5f,
    zoom: Float = 1f,
    showDeviceHeader: Boolean = true,
    showChromeControls: Boolean = true,
    showAndroidNavButtons: Boolean = true,
    showHardwareControls: Boolean = showChromeControls,
    showClipTextControl: Boolean = false,
    showContainerChrome: Boolean = true,
    deviceBorderWidth: Dp = 5.dp,
    deviceCornerRadius: Dp = 10.dp,
    onHoverColor: (String) -> Unit = {},
    passThroughInput: Boolean = true,
    onPickerClick: (String) -> Unit = {},
    onDevicePointClick: (Int, Int) -> Unit = { _, _ -> },
    onRulerResize: (Float, Float) -> Unit = { _, _ -> },
    onPower: () -> Unit = {},
    onVolumeUp: () -> Unit = {},
    onVolumeDown: () -> Unit = {},
    onRotate: () -> Unit = {},
    onCaptureScreenshot: () -> Unit = {},
    onBugReport: () -> Unit = {},
    onRecord: () -> Unit = {},
    recordLabel: String = "Record",
    recordEnabled: Boolean = true,
    recordingCountdown: Int? = null,
    recordingActive: Boolean = false,
    recordingDuration: String? = null,
    showRecord: Boolean = false,
    onClipText: () -> Unit = {},
    onPopOut: () -> Unit = {},
    showPopOut: Boolean = true,
    /** When true this device is shown elsewhere (Andy pop-out or external app); show a placeholder. */
    mirroredElsewhere: Boolean = false,
    /** When true the hand-off target is the device's own native app (Simulator.app / emulator). */
    mirroredInExternalApp: Boolean = false,
    terminalPlacement: DockPlacement? = null,
    onTerminalToggle: ((DockPlacement) -> Unit)? = null,
    registerNativeHost: Boolean = true,
    registerNativeHostFill: Boolean = false,
    mirrorStreamKey: Any? = null,
    surfaceOccluded: Boolean = false,
    foldableEnabled: Boolean = false,
    foldableHingeAngle: Float = 180f,
    foldableProfile: FoldableDisplayProfile? = null,
    /** Verified/expected capture size after foldable open/close (outer vs inner). */
    foldableCaptureHint: MirrorSourceSize? = null,
    onInput: (MirrorInput) -> Unit,
    onConnect: () -> Unit,
) {
    val windowResizing = LocalWindowResizing.current
    val containerShape = RoundedCornerShape(if (showContainerChrome) AndyRadius.Control else 0.dp)
    val containerModifier = if (showContainerChrome) {
        modifier
            .background(AndyColors.Neutral800.copy(alpha = 0.82f), containerShape)
            .border(1.dp, Border, containerShape)
            .noiseGridOverlay(0.025f)
            .padding(AndySpace.Space5)
    } else {
        modifier.background(Color.Black)
    }
    Row(
        containerModifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHardwareControls) {
            LiveHardwareToolbar(
                enabled = serial != null,
                onPower = onPower,
                onVolumeUp = onVolumeUp,
                onVolumeDown = onVolumeDown,
                onRotate = onRotate,
                onCaptureScreenshot = onCaptureScreenshot,
                onBugReport = onBugReport,
                onRecord = onRecord,
                recordLabel = recordLabel,
                recordEnabled = recordEnabled,
                recordingDuration = recordingDuration,
                showRecord = showRecord,
                onClipText = onClipText,
                showPopOut = showPopOut,
                onPopOut = onPopOut,
            )
        } else if (showClipTextControl || showPopOut) {
            LiveClipTextToolbar(
                enabled = serial != null,
                onClipText = onClipText.takeIf { showClipTextControl },
                showPopOut = showPopOut,
                onPopOut = onPopOut,
            )
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showDeviceHeader && serial != null) {
                val streamChips = remember(
                    mirrorSession,
                    frame,
                    mirrorStatus,
                    mirrorTelemetry,
                    foldableEnabled,
                    foldableHingeAngle,
                ) {
                    val structured = liveStreamChips(mirrorSession, frame, mirrorStatus).toMutableList()
                    if (foldableEnabled) {
                        val posture = foldablePostureForAngle(foldableHingeAngle)
                        structured += LiveStreamChip("Posture: ${posture.label}")
                    }
                    if (structured.isNotEmpty()) {
                        structured
                    } else {
                        mirrorTelemetry.takeIf { it.isNotBlank() }?.let { listOf(LiveStreamChip(it)) }.orEmpty()
                    }
                }
                LiveStreamHeader(
                    chips = streamChips,
                    terminalPlacement = terminalPlacement,
                    onTerminalToggle = onTerminalToggle,
                )
                Spacer(Modifier.height(10.dp))
            }

            BoxWithConstraints(
                Modifier.weight(1f).clip(RoundedCornerShape(deviceCornerRadius)),
                contentAlignment = Alignment.Center,
            ) {
                val viewportWidth = maxWidth
                val zoomFactor = zoom.coerceIn(0.5f, 4f)
                val streamSource = liveMirrorStreamSize(
                    device = device,
                    frame = frame,
                    session = mirrorSession,
                    foldableProfile = foldableProfile.takeIf { foldableEnabled },
                    foldableHingeAngle = foldableHingeAngle,
                )
                val frameSource = liveMirrorFrameSize(frame, mirrorSession)
                val layoutSource = liveMirrorLayoutSize(
                    device = device,
                    frame = frame,
                    session = mirrorSession,
                    captureHint = foldableCaptureHint,
                    foldableProfile = foldableProfile.takeIf { foldableEnabled },
                    foldableHingeAngle = foldableHingeAngle,
                )
                val sourceWidth = frameSource.width
                val sourceHeight = frameSource.height
                val scaledDevicePointClick: (Int, Int) -> Unit = { x, y ->
                    if (
                        boundsDisplayWidth != null && boundsDisplayHeight != null &&
                        sourceWidth > 0 && sourceHeight > 0
                    ) {
                        val (displayX, displayY) = scalePointFromStreamToDisplay(
                            x, y, boundsDisplayWidth, boundsDisplayHeight, sourceWidth, sourceHeight,
                        )
                        onDevicePointClick(displayX, displayY)
                    } else {
                        onDevicePointClick(x, y)
                    }
                }
                val aspect = layoutSource.width.toFloat() / layoutSource.height.toFloat()
                val navHeight = if (showChromeControls) 60.dp else 0.dp
                val viewportHeight = (maxHeight - navHeight).coerceAtLeast(1.dp)
                // Keep the host fitted to the pane so Metal never paints outside Compose bounds.
                // Zoom-in pans inside the fixed frame; zoom-out shrinks the fitted host.
                val layoutScale = zoomFactor.coerceAtMost(1f)
                val contentZoomFactor = zoomFactor.coerceAtLeast(1f)
                val baseWidth = minOf(viewportWidth, viewportHeight * aspect) * layoutScale
                val mirrorHeight = baseWidth / aspect
                // SwingPanel/Metal steals pointer events, so Compose scroll cannot pan.
                // Pan is applied in the native surface and synced back here.
                var contentPanX by remember { mutableFloatStateOf(0f) }
                var contentPanY by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(contentZoomFactor) {
                    if (contentZoomFactor <= 1.01f) {
                        contentPanX = 0f
                        contentPanY = 0f
                    }
                }
                val panX = if (contentZoomFactor > 1.01f) contentPanX else 0f
                val panY = if (contentZoomFactor > 1.01f) contentPanY else 0f
                Box(
                    Modifier
                        .width(baseWidth)
                        .height(mirrorHeight + navHeight),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspect)
                                .background(Color.Black, RoundedCornerShape(deviceCornerRadius))
                                .then(
                                    if (deviceBorderWidth > 0.dp) {
                                        Modifier.border(deviceBorderWidth, Color(0xFF111111), RoundedCornerShape(deviceCornerRadius))
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val mirrorLoading = isMirrorSurfaceLoading(serial, frame, mirrorSession, mirrorStatus)
                            // Defer the GPU host until a decoded frame is buffered so the loading overlay stays visible.
                            if (mirroredElsewhere) {
                                Column(
                                    Modifier.fillMaxSize().padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        if (mirroredInExternalApp) "Viewing in the device’s own app" else "Open in a pop-out window",
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (mirroredInExternalApp) {
                                            "This simulator is shown in Simulator.app. Close that window to mirror here again."
                                        } else {
                                            "This device is mirroring in its own Andy window. Close that window to view it here."
                                        },
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                    if (mirroredInExternalApp) {
                                        Spacer(Modifier.height(12.dp))
                                        OutlinedButton(onClick = onPopOut) {
                                            Text("Mirror in Andy again", fontSize = 12.sp)
                                        }
                                    }
                                }
                            } else if (serial != null || frame != null) {
                                val surfaceOverlay = MirrorOverlay(
                                    highlightBounds = highlightBounds,
                                    boundsDisplayWidth = boundsDisplayWidth,
                                    boundsDisplayHeight = boundsDisplayHeight,
                                    sourceWidth = sourceWidth,
                                    sourceHeight = sourceHeight,
                                    showGrid = gridSize != null,
                                    gridSize = gridSize ?: 16f,
                                    gridColor = gridColor,
                                    showRuler = showRuler,
                                    rulerColor = Rust,
                                    rulerWidth = rulerWidth,
                                    rulerHeight = rulerHeight,
                                    rulerX = rulerX,
                                    rulerY = rulerY,
                                    pickerColor = pickerColor,
                                    pickerHex = pickerHex,
                                    referenceImagePath = referenceImagePath,
                                    referenceImageKey = referenceImageKey,
                                    referenceImageOpacity = referenceImageOpacity,
                                    contentZoom = contentZoomFactor,
                                    contentPanX = panX,
                                    contentPanY = panY,
                                )
                                val deferNative = mirrorLoading && device != null
                                val inputEnabled = passThroughInput
                                val onContentPan: (Float, Float) -> Unit = { x, y ->
                                    contentPanX = x.coerceIn(0f, 1f)
                                    contentPanY = y.coerceIn(0f, 1f)
                                }
                                if (frameFlow != null) {
                                    MirrorVideoSurface(
                                        frames = frameFlow,
                                        resetKey = mirrorStreamKey ?: serial,
                                        modifier = Modifier.fillMaxSize(),
                                        onInput = onInput,
                                        onHoverColor = onHoverColor,
                                        passThroughInput = inputEnabled,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = scaledDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        onContentPan = onContentPan,
                                        overlay = surfaceOverlay,
                                        occluded = surfaceOccluded,
                                        deferNativePresentation = deferNative,
                                        nativePresentation = registerNativeHost,
                                        nativePresentationFillHost = registerNativeHostFill,
                                        gpuMirrorStreamKey = serial.takeIf { registerNativeHost },
                                    )
                                } else {
                                    MirrorVideoSurface(
                                        frame = frame,
                                        modifier = Modifier.fillMaxSize(),
                                        onInput = onInput,
                                        onHoverColor = onHoverColor,
                                        passThroughInput = inputEnabled,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = scaledDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        onContentPan = onContentPan,
                                        overlay = surfaceOverlay,
                                        occluded = surfaceOccluded,
                                        deferNativePresentation = deferNative,
                                        nativePresentation = registerNativeHost,
                                        nativePresentationFillHost = registerNativeHostFill,
                                        gpuMirrorStreamKey = serial.takeIf { registerNativeHost },
                                    )
                                }
                                if (showRuler) {
                                    RulerDistanceLabels(
                                        rulerX = rulerX,
                                        rulerY = rulerY,
                                        sourceWidth = sourceWidth,
                                        sourceHeight = sourceHeight,
                                        contentZoom = contentZoomFactor,
                                        contentPanX = panX,
                                        contentPanY = panY,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                if (mirrorLoading) {
                                    MirrorLoadingOverlay(mirrorStatus)
                                }
                                if (windowResizing) {
                                    MirrorLoadingOverlay("Resizing window…")
                                }
                                if (recordingCountdown != null) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.56f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Recording starts in", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text(recordingCountdown.toString(), color = Rust, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else if (recordingActive) {
                                    Text(
                                        "● REC",
                                        color = Rust,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                                    )
                                }
                            } else {
                                Text(
                                    "Connect a device to display",
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        if (showChromeControls) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showAndroidNavButtons) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable(enabled = serial != null) { onInput(MirrorInput.Back) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        NavIconBack(color = if (serial != null) TextPrimary else TextSecondary)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(enabled = serial != null) { onInput(MirrorInput.Home) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    NavIconHome(color = if (serial != null) TextPrimary else TextSecondary)
                                }
                                if (showAndroidNavButtons) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable(enabled = serial != null) { onInput(MirrorInput.Recents) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        NavIconRecents(color = if (serial != null) TextPrimary else TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulerDistanceLabels(
    rulerX: Float,
    rulerY: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    contentZoom: Float,
    contentPanX: Float,
    contentPanY: Float,
    modifier: Modifier = Modifier,
) {
    val width = sourceWidth.coerceAtLeast(1)
    val height = sourceHeight.coerceAtLeast(1)
    val xPx = rulerX.coerceIn(0f, width.toFloat())
    val yPx = rulerY.coerceIn(0f, height.toFloat())
    val zoom = contentZoom.coerceAtLeast(1f)
    val originX = contentPanX.coerceIn(0f, 1f) * (1f - 1f / zoom)
    val originY = contentPanY.coerceIn(0f, 1f) * (1f - 1f / zoom)
    fun contentToHost(contentFrac: Float, origin: Float): Float =
        ((contentFrac - origin) * zoom).coerceIn(0f, 1f)
    val hostX = contentToHost(xPx / width, originX)
    val hostY = contentToHost(yPx / height, originY)
    BoxWithConstraints(modifier) {
        val xDp = maxWidth * hostX
        val yDp = maxHeight * hostY
        Box(
            Modifier
                .offset(x = xDp)
                .width(1.dp)
                .fillMaxHeight()
                .background(Rust.copy(alpha = 0.95f)),
        )
        Box(
            Modifier
                .offset(y = yDp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Rust.copy(alpha = 0.95f)),
        )
        RulerBadge(
            xPx.roundToInt().toString(),
            Modifier.offset(x = (xDp + 8.dp).coerceAtMost(maxWidth - 48.dp), y = 8.dp),
        )
        RulerBadge(
            (width - xPx).roundToInt().toString(),
            Modifier.offset(
                x = (xDp - 48.dp).coerceAtLeast(4.dp),
                y = (maxHeight - 28.dp).coerceAtLeast(4.dp),
            ),
        )
        RulerBadge(
            yPx.roundToInt().toString(),
            Modifier.offset(x = 8.dp, y = (yDp + 8.dp).coerceAtMost(maxHeight - 28.dp)),
        )
        RulerBadge(
            (height - yPx).roundToInt().toString(),
            Modifier.offset(
                x = (maxWidth - 48.dp).coerceAtLeast(4.dp),
                y = (yDp - 28.dp).coerceAtLeast(4.dp),
            ),
        )
    }
}

@Composable
private fun RulerBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Rust.copy(alpha = 0.96f), RoundedCornerShape(AndyRadius.Control))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun CompactHardwareButton(label: String, serial: String?, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = serial != null,
        modifier = Modifier.widthIn(min = 82.dp).height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LiveClipTextToolbar(
    enabled: Boolean,
    onClipText: (() -> Unit)? = null,
    showPopOut: Boolean = false,
    onPopOut: () -> Unit = {},
) {
    Box(
        Modifier.width(68.dp).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(AndyColors.Neutral900.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(15.dp))
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onClipText != null) {
                ToolbarButton(HardwareIcon.Clip, "Clip", enabled, onClipText)
            }
            if (showPopOut) {
                ToolbarButton(HardwareIcon.PopOut, "Pop out", enabled, onPopOut)
            }
        }
    }
}

@Composable
internal fun LiveHardwareToolbar(
    enabled: Boolean,
    onPower: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onRotate: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onBugReport: () -> Unit,
    onRecord: () -> Unit,
    recordLabel: String,
    recordEnabled: Boolean,
    recordingDuration: String?,
    showRecord: Boolean,
    onClipText: () -> Unit,
    showPopOut: Boolean = false,
    onPopOut: () -> Unit = {},
) {
    Box(
        Modifier.width(68.dp).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(AndyColors.Neutral900.copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.04f), RoundedCornerShape(15.dp))
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ToolbarButton(HardwareIcon.Power, "Power", enabled, onPower)
            ToolbarButton(HardwareIcon.VolumeUp, "Vol +", enabled, onVolumeUp)
            ToolbarButton(HardwareIcon.VolumeDown, "Vol -", enabled, onVolumeDown)
            ToolbarButton(HardwareIcon.Rotate, "Rotate", enabled, onRotate)
            ToolbarButton(HardwareIcon.Capture, "Capture", enabled, onCaptureScreenshot)
            ToolbarButton(HardwareIcon.Bug, "Bug", enabled, onBugReport)
            ToolbarButton(HardwareIcon.Clip, "Clip", enabled, onClipText)
            if (showRecord) {
                ToolbarButton(HardwareIcon.Record, recordLabel, enabled && recordEnabled, onRecord)
                recordingDuration?.let { duration ->
                    Text(duration, color = Red, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
            if (showPopOut) {
                ToolbarButton(HardwareIcon.PopOut, "Pop out", enabled, onPopOut)
            }
        }
    }
}

@Composable
internal fun ToolbarButton(icon: HardwareIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    val contentColor = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .width(54.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HardwareControlIcon(icon, contentColor, Modifier.size(24.dp))
        Text(
            label,
            color = contentColor,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal enum class HardwareIcon {
    Power,
    VolumeUp,
    VolumeDown,
    Rotate,
    Capture,
    Bug,
    Clip,
    PopOut,
    Record,
}

@Composable
internal fun HardwareControlIcon(icon: HardwareIcon, color: Color, modifier: Modifier = Modifier) {
    val resource = when (icon) {
        HardwareIcon.Power -> Res.drawable.hardware_power
        HardwareIcon.VolumeUp -> Res.drawable.hardware_volume_up
        HardwareIcon.VolumeDown -> Res.drawable.hardware_volume_down
        HardwareIcon.Rotate -> Res.drawable.hardware_rotate
        HardwareIcon.Capture -> Res.drawable.hardware_capture
        HardwareIcon.Bug -> Res.drawable.hardware_bug
        HardwareIcon.Clip -> Res.drawable.hardware_clipboard
        HardwareIcon.PopOut -> Res.drawable.hardware_pop_out
        HardwareIcon.Record -> Res.drawable.hardware_record
    }
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}

@Composable
internal fun LiveStreamHeader(
    chips: List<LiveStreamChip>,
    terminalPlacement: DockPlacement? = null,
    onTerminalToggle: ((DockPlacement) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Row(
            Modifier
                .align(Alignment.Center)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chips.isEmpty()) {
                Text("Waiting for stream", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                chips.forEach { chip -> LiveStreamChipView(chip) }
            }
        }
        if (onTerminalToggle != null) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                TerminalDockToggleRow(
                    terminalPlacement = terminalPlacement,
                    onToggle = onTerminalToggle,
                )
            }
        }
    }
}

@Composable
private fun LiveStreamChipView(chip: LiveStreamChip) {
    val accent = when (chip.tone) {
        LiveStreamChipTone.Neutral -> TextSecondary
        LiveStreamChipTone.Active -> Green
        LiveStreamChipTone.Warning -> Rust
    }
    val shape = RoundedCornerShape(AndyRadius.Control)
    Text(
        chip.label,
        color = if (chip.tone == LiveStreamChipTone.Neutral) TextSecondary else TextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(shape)
            .background(AndyColors.Neutral850.copy(alpha = 0.92f))
            .border(1.dp, accent.copy(alpha = if (chip.tone == LiveStreamChipTone.Neutral) 0.22f else 0.48f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
