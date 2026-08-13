package app.andy.terminal.rust

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalFontFamily
import app.andy.terminal.panelBackgroundArgb
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface
import kotlin.math.floor
import kotlin.math.max

/**
 * Compose/Skia painter for [RustTerminalBackend].
 * Andy owns redraw cadence; this composable only paints published frames.
 */
@Composable
fun RustTerminalCanvas(
    backend: RustTerminalBackend,
    appearance: TerminalAppearanceSnapshot,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val tick by backend.frameTick.collectAsState()
    val frame = remember { RustTerminalFrame() }
    val focusRequester = remember { FocusRequester() }
    val panelBg = Color(appearance.panelBackgroundArgb())
    val density = LocalDensity.current
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var lastGrid by remember { mutableStateOf(0 to 0) }

    val fontSizePx = with(density) { appearance.fontSize.toDp().toPx() }
    val typeface = remember(appearance.fontFamily) {
        resolveTypeface(appearance.fontFamily)
    }
    val font = remember(typeface, fontSizePx) {
        Font(typeface, fontSizePx)
    }
    val cellWidth = remember(font) {
        max(font.measureTextWidth("M"), 1f)
    }
    val cellHeight = remember(font, fontSizePx) {
        max(fontSizePx * 1.35f, 1f)
    }
    val textPaint = remember { Paint() }
    val boldFont = remember(typeface, fontSizePx) {
        val boldFace = FontMgr.default.matchFamilyStyle(typeface.familyName, FontStyle.BOLD)
            ?: typeface
        Font(boldFace, fontSizePx)
    }

    LaunchedEffect(tick) {
        backend.copyPaintFrame(frame)
    }

    LaunchedEffect(viewportPx, cellWidth, cellHeight) {
        if (viewportPx.width <= 0 || viewportPx.height <= 0) return@LaunchedEffect
        val cols = floor(viewportPx.width / cellWidth).toInt().coerceAtLeast(1)
        val rows = floor(viewportPx.height / cellHeight).toInt().coerceAtLeast(1)
        val next = cols to rows
        if (next != lastGrid) {
            lastGrid = next
            backend.resize(cols, rows)
        }
    }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier
            .background(panelBg)
            .onSizeChanged { viewportPx = it }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val bytes = encodeTerminalKey(event) ?: return@onPreviewKeyEvent false
                backend.write(bytes)
                true
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Ensure we paint latest even if tick raced.
            if (tick >= 0L) {
                backend.copyPaintFrame(frame)
            }
            paintFrame(
                frame = frame,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                font = font,
                boldFont = boldFont,
                textPaint = textPaint,
                fallbackBg = panelBg,
            )
        }
    }
}

private fun DrawScope.paintFrame(
    frame: RustTerminalFrame,
    cellWidth: Float,
    cellHeight: Float,
    font: Font,
    boldFont: Font,
    textPaint: Paint,
    fallbackBg: Color,
) {
    val cols = frame.columns
    val rows = frame.rows
    if (cols <= 0 || rows <= 0 || frame.chars.isEmpty()) {
        drawRect(fallbackBg)
        return
    }

    val native = drawContext.canvas.skiaCanvas
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val idx = row * cols + col
            if (idx >= frame.chars.size) continue
            var fg = frame.fgArgb[idx]
            var bg = frame.bgArgb[idx]
            val attr = frame.attrs[idx].toInt()
            if (attr and RustTerminalAttrs.INVERSE != 0) {
                val tmp = fg
                fg = bg
                bg = tmp
            }
            val left = col * cellWidth
            val top = row * cellHeight
            drawRect(
                color = Color(bg.toLong() and 0xFFFFFFFFL),
                topLeft = Offset(left, top),
                size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
            )
            val ch = frame.chars[idx]
            if (ch != ' ' && ch != '\u0000') {
                val useBold = attr and RustTerminalAttrs.BOLD != 0
                textPaint.color = fg
                native.drawString(
                    ch.toString(),
                    left,
                    top + cellHeight * 0.78f,
                    if (useBold) boldFont else font,
                    textPaint,
                )
            }
        }
    }

    // Block cursor.
    val cRow = frame.cursorRow
    val cCol = frame.cursorCol
    if (cRow in 0 until rows && cCol in 0 until cols) {
        drawRect(
            color = Color(0xFF_AB_B2_BF),
            topLeft = Offset(cCol * cellWidth, cRow * cellHeight),
            size = Size(cellWidth, cellHeight),
            alpha = 0.35f,
        )
    }
}

private fun resolveTypeface(family: TerminalFontFamily): Typeface {
    val candidates = listOfNotNull(family.awtName, "Menlo", "SF Mono", "Monaco", "monospace")
    for (name in candidates) {
        FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL)?.let { return it }
    }
    return Typeface.makeEmpty()
}
