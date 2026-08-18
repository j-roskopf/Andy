package app.andy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.desktop.mermaid.MermaidJni
import app.andy.desktop.mermaid.MermaidNative
import app.andy.loadImageBitmap
import app.andy.rememberCopyText
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.AndyStroke
import app.andy.ui.theme.Border
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import dev.snipme.highlights.Highlights
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MinZoom = 0.15f
private const val MaxZoom = 8f
private val InlineMaxHeight = 280.dp
private val InlineMinHeight = 64.dp

@Composable
internal actual fun MermaidFence(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    val dark = !AndyColors.isLight
    var png by remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(dark) { png = null }
    LaunchedEffect(code, dark) {
        if (!MermaidNative.isAvailable() || code.isBlank()) return@LaunchedEffect
        delay(180)
        png = withContext(Dispatchers.Default) {
            MermaidJni.renderPng(code, dark).getOrNull()
        }
    }
    val bitmap = remember(png) { png?.let { loadImageBitmap(it) } }
    if (bitmap == null) {
        SafeMarkdownHighlightedCode(
            code = code,
            language = language,
            style = style,
            highlightsBuilder = highlightsBuilder,
            showHeader = showHeader,
        )
        return
    }
    MermaidPreview(
        source = code,
        bitmap = bitmap,
        showHeader = showHeader,
    )
}

@Composable
private fun MermaidPreview(
    source: String,
    bitmap: ImageBitmap,
    showHeader: Boolean,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    var fullscreen by remember { mutableStateOf(false) }

    DisableSelection {
        MarkdownCodeBackground(
            color = backgroundCodeColor,
            shape = RoundedCornerShape(codeBackgroundCornerSize),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            showHeader = false,
            language = "mermaid",
            code = source,
        ) {
            Column {
                if (showHeader) {
                    MermaidChrome(
                        source = source,
                        scaleLabel = null,
                        onFit = null,
                        onActual = null,
                        onZoomIn = null,
                        onZoomOut = null,
                        onExpand = { fullscreen = true },
                    )
                    HorizontalDivider(
                        thickness = AndyStroke.Hairline,
                        color = LocalMarkdownColors.current.dividerColor.copy(alpha = 0.3f),
                    )
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = codeBackgroundCornerSize, bottomEnd = codeBackgroundCornerSize))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { role = Role.Button }
                        .clickable(onClickLabel = "Open mermaid diagram") { fullscreen = true }
                        .padding(AndySpace.Space2),
                ) {
                    val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat()
                    val height = (maxWidth / aspect).coerceIn(InlineMinHeight, InlineMaxHeight)
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Mermaid diagram",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(height),
                    )
                }
            }
        }
        if (fullscreen) {
            MermaidFullscreenDialog(
                source = source,
                bitmap = bitmap,
                onDismiss = { fullscreen = false },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MermaidFullscreenDialog(
    source: String,
    bitmap: ImageBitmap,
    onDismiss: () -> Unit,
) {
    val imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember(bitmap) { mutableStateOf(false) }

    fun fitToViewport(size: IntSize = viewport) {
        if (size.width <= 0 || size.height <= 0 || imageSize.width <= 0f || imageSize.height <= 0f) return
        val next = min(size.width / imageSize.width, size.height / imageSize.height).coerceIn(MinZoom, MaxZoom)
        scale = next
        pan = Offset(
            (size.width - imageSize.width * next) / 2f,
            (size.height - imageSize.height * next) / 2f,
        )
        fitted = true
    }

    fun zoomAt(factor: Float, anchor: Offset) {
        val next = (scale * factor).coerceIn(MinZoom, MaxZoom)
        if (next == scale) return
        val world = Offset(
            (anchor.x - pan.x) / scale,
            (anchor.y - pan.y) / scale,
        )
        scale = next
        pan = Offset(anchor.x - world.x * next, anchor.y - world.y * next)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AndySpace.Space4)
                .clip(RoundedCornerShape(AndyRadius.Sheet))
                .background(AndyColors.Neutral900)
                .border(1.dp, Border, RoundedCornerShape(AndyRadius.Sheet)),
        ) {
            MermaidChrome(
                source = source,
                scaleLabel = "${(scale * 100).toInt()}%",
                onFit = { fitToViewport() },
                onActual = {
                    scale = 1f
                    pan = Offset(
                        ((viewport.width - imageSize.width) / 2f),
                        ((viewport.height - imageSize.height) / 2f),
                    )
                },
                onZoomIn = {
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    zoomAt(1.25f, center)
                },
                onZoomOut = {
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    zoomAt(0.8f, center)
                },
                onExpand = onDismiss,
                expandLabel = "Close",
            )
            HorizontalDivider(thickness = AndyStroke.Hairline, color = Border.copy(alpha = 0.45f))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        viewport = size
                        if (!fitted) fitToViewport(size)
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Main) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val delta = change.scrollDelta.y
                        if (delta == 0f) return@onPointerEvent
                        val factor = if (delta < 0f) 1.1f else 1f / 1.1f
                        zoomAt(factor, change.position)
                        change.consume()
                    }
                    .pointerInput(bitmap) {
                        detectTapGestures(onDoubleTap = { fitToViewport() })
                    }
                    .pointerInput(bitmap) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            pan += dragAmount
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val dstW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val dstH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(pan.x.roundToInt(), pan.y.roundToInt()),
                        dstSize = IntSize(dstW, dstH),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MermaidChrome(
    source: String,
    scaleLabel: String?,
    onFit: (() -> Unit)?,
    onActual: (() -> Unit)?,
    onZoomIn: (() -> Unit)?,
    onZoomOut: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    expandLabel: String = "Expand",
) {
    val copyText = rememberCopyText()
    val textColor = LocalMarkdownColors.current.text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MERMAID",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = textColor.copy(alpha = 0.6f),
            )
            scaleLabel?.let {
                Text(
                    text = it,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onZoomOut != null) MermaidChromeAction("−", "Zoom out", onZoomOut)
            if (onZoomIn != null) MermaidChromeAction("+", "Zoom in", onZoomIn)
            if (onFit != null) MermaidChromeAction("Fit", "Fit diagram", onFit)
            if (onActual != null) MermaidChromeAction("100%", "Actual size", onActual)
            MermaidChromeAction("Copy", "Copy mermaid source") { copyText(source) }
            if (onExpand != null) MermaidChromeAction(expandLabel, expandLabel, onExpand)
        }
    }
}

@Composable
private fun MermaidChromeAction(label: String, onClickLabel: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = TextPrimary.copy(alpha = 0.78f),
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { role = Role.Button }
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    )
}
