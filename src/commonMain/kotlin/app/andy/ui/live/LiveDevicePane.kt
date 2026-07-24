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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.andy.model.AndroidDevice
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.ui.actions.DockPlacement
import app.andy.ui.actions.TerminalDockToggleRow
import app.andy.ui.components.noiseGridOverlay
import app.andy.ui.components.OutlinedButton
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

internal data class MirrorSourceSize(val width: Int, val height: Int)

/**
 * Size used for the Live host aspect ratio. Prefer the active stream frame so Compose and
 * Metal letterboxing stay aligned; fall back to [AndroidDevice.screenSize], then a tall-phone
 * default (not the old 720x1280 fallback, which made first-boot Live look too wide).
 */
internal fun liveMirrorSourceSize(device: AndroidDevice?, frame: MirrorFrame?): MirrorSourceSize {
    if (frame != null && frame.width > 1 && frame.height > 1) {
        return MirrorSourceSize(frame.width, frame.height)
    }
    val raw = device?.screenSize
    if (raw != null) {
        val width = raw.substringBefore('x').toIntOrNull()
        val height = raw.substringAfter('x').toIntOrNull()
        if (width != null && height != null && width > 1 && height > 1 && 'x' in raw) {
            return MirrorSourceSize(width, height)
        }
    }
    return MirrorSourceSize(1080, 2400)
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
    onInput: (MirrorInput) -> Unit,
    onConnect: () -> Unit,
) {
    val windowResizing = LocalWindowResizing.current
    val containerShape = RoundedCornerShape(if (showContainerChrome) AndyRadius.R3 else 0.dp)
    val containerModifier = if (showContainerChrome) {
        modifier
            .background(AndyColors.Neutral800.copy(alpha = 0.82f), containerShape)
            .border(1.dp, Border, containerShape)
            .noiseGridOverlay(0.025f)
            .padding(AndySpace.S4)
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
            )
        } else if (showClipTextControl) {
            LiveClipTextToolbar(
                enabled = serial != null,
                onClipText = onClipText,
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showDeviceHeader && serial != null) {
                val streamChips = remember(mirrorSession, frame, mirrorStatus, mirrorTelemetry) {
                    val structured = liveStreamChips(mirrorSession, frame, mirrorStatus)
                    if (structured.isNotEmpty()) structured else mirrorTelemetry.takeIf { it.isNotBlank() }?.let { listOf(LiveStreamChip(it)) } ?: emptyList()
                }
                LiveStreamHeader(
                    chips = streamChips,
                    showPopOut = showPopOut,
                    popOutEnabled = true,
                    onPopOut = onPopOut,
                    terminalPlacement = terminalPlacement,
                    onTerminalToggle = onTerminalToggle,
                )
                Spacer(Modifier.height(10.dp))
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val viewportWidth = maxWidth
                val zoomFactor = zoom.coerceIn(0.5f, 4f)
                // Prefer the live stream size so the Compose host matches Metal letterboxing.
                // device.screenSize can lag or disagree on first emulator boot.
                val source = liveMirrorSourceSize(device, frame)
                val sourceWidth = source.width
                val sourceHeight = source.height
                val aspect = sourceWidth.toFloat() / sourceHeight.toFloat()
                val navHeight = if (showChromeControls) 60.dp else 0.dp
                val viewportHeight = (maxHeight - navHeight).coerceAtLeast(1.dp)
                val baseWidth = minOf(viewportWidth, viewportHeight * aspect)
                Box(
                    Modifier.fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier.width(baseWidth * zoomFactor),
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
                            // Heavyweight desktop surfaces render above Compose. Defer the GPU
                            // host until a decoded frame is buffered so this overlay stays visible.
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
                                )
                                if (frameFlow != null) {
                                    MirrorVideoSurface(
                                        frames = frameFlow,
                                        resetKey = mirrorStreamKey ?: serial,
                                        modifier = Modifier.fillMaxSize(),
                                        onInput = onInput,
                                        onHoverColor = onHoverColor,
                                        passThroughInput = passThroughInput,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = onDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        overlay = surfaceOverlay,
                                        occluded = surfaceOccluded,
                                        deferNativePresentation = mirrorLoading && device != null,
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
                                        passThroughInput = passThroughInput,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = onDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        overlay = surfaceOverlay,
                                        occluded = surfaceOccluded,
                                        deferNativePresentation = mirrorLoading && device != null,
                                        nativePresentation = registerNativeHost,
                                        nativePresentationFillHost = registerNativeHostFill,
                                        gpuMirrorStreamKey = serial.takeIf { registerNativeHost },
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
    onClipText: () -> Unit,
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
            ToolbarButton(HardwareIcon.Clip, "Clip", enabled, onClipText)
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
    showPopOut: Boolean,
    popOutEnabled: Boolean,
    onPopOut: () -> Unit,
    terminalPlacement: DockPlacement? = null,
    onTerminalToggle: ((DockPlacement) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
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
            TerminalDockToggleRow(
                terminalPlacement = terminalPlacement,
                onToggle = onTerminalToggle,
            )
        }
        if (showPopOut) {
            OutlinedButton(
                onClick = onPopOut,
                enabled = popOutEnabled,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                HardwareControlIcon(
                    HardwareIcon.PopOut,
                    if (popOutEnabled) TextPrimary else TextSecondary.copy(alpha = 0.38f),
                    Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Pop out", fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
    val shape = RoundedCornerShape(AndyRadius.R2)
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
