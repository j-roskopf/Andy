package app.andy.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidDevice
import app.andy.loadImageBitmap
import app.andy.pickFiles
import app.andy.rememberCopyText
import app.andy.service.AndyServices
import app.andy.ui.components.FilterPill
import app.andy.ui.components.FormRow
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PaneDivider
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DesignScreen(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    devicePaneWidth: Float,
    onDevicePaneWidthChange: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Design overlays") }
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var ruler by remember { mutableStateOf(false) }
    var gridSize by remember { mutableStateOf("16") }
    var rulerX by remember { mutableStateOf("540") }
    var rulerY by remember { mutableStateOf("960") }
    var color by remember { mutableStateOf(Cyan) }
    var pickerEnabled by remember { mutableStateOf(false) }
    var pickedColor by remember { mutableStateOf("#------") }
    var pickerToast by remember { mutableStateOf<String?>(null) }
    var referenceImagePath by remember { mutableStateOf<String?>(null) }
    var referenceImageKey by remember { mutableLongStateOf(0L) }
    var referenceImageOpacity by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableStateOf("1.0") }
    var localDevicePaneWidth by remember(devicePaneWidth) { mutableStateOf(devicePaneWidth.coerceAtLeast(760f)) }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    val copyText = rememberCopyText()
    LaunchedEffect(pickerToast) {
        if (pickerToast != null) {
            delay(1800)
            pickerToast = null
        }
    }
    LaunchedEffect(Unit) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(serial) {
        if (serial != null) {
            val result = services.mirror.connect(serial)
            connectResult = if (result.isSuccess) result.stdout else result.stderr
        }
    }
    Row(Modifier.fillMaxSize()) {
        MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
            LiveDevicePane(
                serial = serial,
                device = device,
                frame = frame,
                frameFlow = frameFlow,
                mirrorStatus = mirrorStatus,
                connectResult = connectResult,
                modifier = Modifier.width(localDevicePaneWidth.dp).fillMaxHeight(),
                showRuler = ruler,
                rulerX = rulerX.toFloatOrNull()?.coerceIn(0f, 3000f) ?: 540f,
                rulerY = rulerY.toFloatOrNull()?.coerceIn(0f, 3000f) ?: 960f,
                gridSize = if (grid) gridSize.toFloatOrNull()?.coerceIn(2f, 120f) else null,
                gridColor = color.copy(alpha = 0.38f),
                pickerColor = color.takeIf { pickerEnabled },
                pickerHex = pickedColor,
                referenceImagePath = referenceImagePath,
                referenceImageKey = referenceImageKey,
                referenceImageOpacity = referenceImageOpacity,
                zoom = zoom.toFloatOrNull()?.coerceIn(0.5f, 4f) ?: 1f,
                onHoverColor = { hex ->
                    if (pickerEnabled) pickedColor = hex
                },
                passThroughInput = !pickerEnabled,
                onPickerClick = { hex ->
                    pickedColor = hex
                    copyText(hex)
                    pickerToast = "Copied $hex"
                },
                onRulerResize = { x, y ->
                    rulerX = x.toInt().toString()
                    rulerY = y.toInt().toString()
                },
                showPopOut = services.capabilities.platform != app.andy.service.AndyPlatform.Web,
                onInput = sendMirrorInput,
                onConnect = {
                    if (serial != null) scope.launch {
                        val result = services.mirror.connect(serial)
                        connectResult = if (result.isSuccess) result.stdout else result.stderr
                    }
                },
            )
        }
        Spacer(Modifier.width(6.dp))
        PaneDivider(
            onDrag = { dragX -> localDevicePaneWidth = (localDevicePaneWidth + dragX).coerceIn(640f, 1900f) },
            onDragEnd = { onDevicePaneWidthChange(localDevicePaneWidth) },
        )
        PanelCard(Modifier.fillMaxSize().padding(start = 6.dp)) {
            Text("Design overlay", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("Ruler", ruler, Yellow) { ruler = !ruler }
                FilterPill("Grid", grid, Cyan) { grid = !grid }
                FilterPill("Picker", pickerEnabled, Rust) { pickerEnabled = !pickerEnabled }
            }
            FormRow("Grid size") {
                TextField(gridSize, { gridSize = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(120.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
            }
            FormRow("Ruler X/Y") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(rulerX, { rulerX = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    TextField(rulerY, { rulerY = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                }
            }
            FormRow("Zoom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val next = ((zoom.toFloatOrNull() ?: 1f) - 0.25f).coerceIn(0.5f, 4f)
                        zoom = app.andy.formatDecimal(next, 2)
                    }) { Text("-") }
                    TextField(zoom, { zoom = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true, modifier = Modifier.width(96.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    OutlinedButton(onClick = {
                        val next = ((zoom.toFloatOrNull() ?: 1f) + 0.25f).coerceIn(0.5f, 4f)
                        zoom = app.andy.formatDecimal(next, 2)
                    }) { Text("+") }
                }
            }
            Text("Reference image", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val path = pickFiles(allowMultiple = false).firstOrNull() ?: return@launch
                        val canLoad = withContext(Dispatchers.Default) { loadImageBitmap(path) != null }
                        if (canLoad) {
                            referenceImagePath = path
                            referenceImageKey++
                            status = "Image overlay: ${path.fileName()}"
                        } else {
                            status = "Couldn't load ${path.fileName()} as an image"
                        }
                    }
                }) {
                    Text(if (referenceImagePath == null) "Upload image" else "Replace image")
                }
                if (referenceImagePath != null) {
                    OutlinedButton(onClick = {
                        referenceImagePath = null
                        status = "Image overlay removed"
                    }) { Text("Remove image") }
                }
            }
            if (referenceImagePath != null) {
                Text(referenceImagePath.orEmpty().fileName(), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(62.dp))
                    Slider(
                        value = referenceImageOpacity,
                        onValueChange = { referenceImageOpacity = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(referenceImageOpacity * 100).toInt()}%",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.width(46.dp),
                    )
                }
            }
            Text("Grid color", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Cyan, Rust, Green, Yellow, Red, Color.White).forEach { swatch ->
                    Box(
                        Modifier.size(34.dp)
                            .background(swatch, RoundedCornerShape(6.dp))
                            .border(if (swatch == color) 2.dp else 1.dp, if (swatch == color) TextPrimary else Border, RoundedCornerShape(6.dp))
                            .clickable { color = swatch },
                    )
                }
            }
            Text("Color picker", color = TextPrimary, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp)
                        .background(pickedColor.toColorOrNull() ?: Color.Transparent, RoundedCornerShape(4.dp))
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                )
                Text("Under pointer $pickedColor · swatch ${color.toHex()}", color = if (pickerEnabled) TextPrimary else TextSecondary)
            }
            AnimatedVisibility(
                visible = pickerToast != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .background(Rust.copy(alpha = 0.18f), RoundedCornerShape(AndyRadius.Pill))
                        .border(1.dp, Rust.copy(alpha = 0.45f), RoundedCornerShape(AndyRadius.Pill))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(pickerToast.orEmpty(), color = Rust, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private fun String.toColorOrNull(): Color? {
    if (!matches(Regex("""#[0-9A-Fa-f]{6}"""))) return null
    return Color(
        red = substring(1, 3).toInt(16),
        green = substring(3, 5).toInt(16),
        blue = substring(5, 7).toInt(16),
    )
}

private fun Color.toHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return app.andy.formatRgbHex(r, g, b)
}

private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')
