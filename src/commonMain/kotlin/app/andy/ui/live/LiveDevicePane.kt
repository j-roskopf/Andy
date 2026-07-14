package app.andy.ui.live

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
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
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun LiveDevicePane(
    serial: String?,
    device: AndroidDevice?,
    displayName: String? = device?.displayName,
    frame: MirrorFrame?,
    frameFlow: Flow<MirrorFrame>? = null,
    mirrorStatus: String,
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
    showHardwareControls: Boolean = showChromeControls,
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
    onClipText: () -> Unit = {},
    onPopOut: () -> Unit = {},
    onInput: (MirrorInput) -> Unit,
    onConnect: () -> Unit,
) {
    val containerShape = RoundedCornerShape(if (showContainerChrome) 8.dp else 0.dp)
    val containerModifier = if (showContainerChrome) {
        modifier.background(PanelSoft, containerShape).padding(14.dp)
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
                onClipText = onClipText,
                onPopOut = onPopOut,
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showDeviceHeader && serial != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName ?: device?.serial ?: serial,
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            frame?.let { "${it.width}×${it.height} stream" },
                            frame?.displayedFps?.let { "${app.andy.formatDecimal(it, 1)} displayed fps" },
                            frame?.decodedFps?.let { "${app.andy.formatDecimal(it, 1)} decoded fps" },
                        ).joinToString(" · ").ifBlank { mirrorStatus },
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val viewportWidth = maxWidth
                val zoomFactor = zoom.coerceIn(0.5f, 4f)
                val sourceWidth = (device?.screenSize?.substringBefore("x")?.toIntOrNull() ?: frame?.width ?: 1080).coerceAtLeast(1)
                val sourceHeight = (device?.screenSize?.substringAfter("x")?.toIntOrNull() ?: frame?.height ?: 2340).coerceAtLeast(1)
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
                            if (frame != null) {
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
                                        resetKey = serial,
                                        modifier = Modifier.fillMaxSize(),
                                        onInput = onInput,
                                        onHoverColor = onHoverColor,
                                        passThroughInput = passThroughInput,
                                        onPickerClick = onPickerClick,
                                        onDevicePointClick = onDevicePointClick,
                                        onRulerResize = onRulerResize,
                                        overlay = surfaceOverlay,
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
                                    )
                                }
                            }
                        }

                        if (showChromeControls) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(enabled = serial != null) { onInput(MirrorInput.Back) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    NavIconBack(color = if (serial != null) TextPrimary else TextSecondary)
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
internal fun ClipTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Clip text", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            TextField(
                text,
                { text = it },
                modifier = Modifier.fillMaxWidth().height(110.dp),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
            )
        },
        confirmButton = {
            Button(onClick = { onSend(text) }, enabled = text.isNotBlank(), colors = primaryButtonColors()) { Text("Send") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
    onClipText: () -> Unit,
    onPopOut: () -> Unit,
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
            ToolbarButton(HardwareIcon.PopOut, "Pop Out", enabled, onPopOut)
            ToolbarButton(HardwareIcon.Record, "Record", false) {}
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
