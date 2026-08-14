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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.andy.model.TerminalAppearanceSnapshot
import app.andy.model.TerminalFontFamily
import app.andy.model.panelBackgroundArgb
import app.andy.rememberCopyText
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Compose/Skia painter for a [RustTerminalRenderable] (live PTY or history replay).
 * Andy owns redraw cadence; this composable paints published frames and owns
 * keyboard / mouse / selection / local scrollback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RustTerminalCanvas(
    backend: RustTerminalRenderable,
    appearance: TerminalAppearanceSnapshot,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    readOnly: Boolean = false,
    /**
     * Cap the negotiated column count so a wide pane doesn't hand the CLI a line length
     * it happily fills edge to edge — text stays a comfortable width and the canvas just
     * letterboxes the rest. Null keeps the historical fill-the-pane behavior (plain shells).
     */
    maxCols: Int? = null,
) {
    val tick by backend.frameTick.collectAsState()
    val frame = remember { RustTerminalFrame() }
    val focusRequester = remember { FocusRequester() }
    val panelBg = Color(appearance.panelBackgroundArgb())
    val selectionBg = Color(appearance.selectionArgb().toLong() and 0xFFFFFFFFL)
    val selectionFg = Color(appearance.selectionTextArgb().toLong() and 0xFFFFFFFFL)
    val cursorColor = Color(appearance.toRustPaletteArgb()[2].toLong() and 0xFFFFFFFFL)
    val density = LocalDensity.current
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var lastGrid by remember { mutableStateOf(0 to 0) }
    var selection by remember { mutableStateOf<CellRange?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val copyText = rememberCopyText()
    val wheel = remember(backend) {
        RustWheelAccumulator { bytes -> backend.write(bytes) }
    }
    var localScrollAccum by remember { mutableStateOf(0f) }

    // appearance.fontSize is a logical size (dp-like), not physical pixels. Density.toDp()
    // treats its receiver as pixels and divides by density, so `.toDp().toPx()` was a
    // self-canceling round trip that silently dropped HiDPI scaling — on a 2x Retina
    // display a "12" font rendered at 12 physical px instead of the intended 24.
    val fontSizePx = with(density) { appearance.fontSize.dp.toPx() }
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
    // The primary monospace face (Menlo/SF Mono/etc.) doesn't cover Nerd Font/powerline
    // glyphs or box-drawing characters that shell prompts commonly emit. Without a fallback,
    // Skia draws those codepoints as a ".notdef" tofu box, which reads as a stray "??" icon.
    val fallbackFonts = remember(typeface, fontSizePx) { mutableMapOf<Int, Font?>() }

    LaunchedEffect(appearance) {
        backend.updateAppearance(appearance)
    }

    LaunchedEffect(tick) {
        backend.copyPaintFrame(frame)
    }

    LaunchedEffect(viewportPx, cellWidth, cellHeight, maxCols) {
        if (viewportPx.width <= 0 || viewportPx.height <= 0) return@LaunchedEffect
        val rawCols = floor(viewportPx.width / cellWidth).toInt().coerceAtLeast(1)
        val cols = maxCols?.let { min(rawCols, it) } ?: rawCols
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

    fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val col = floor(x / cellWidth).toInt().coerceIn(0, (frame.columns - 1).coerceAtLeast(0))
        val row = floor(y / cellHeight).toInt().coerceIn(0, (frame.rows - 1).coerceAtLeast(0))
        return col to row
    }

    fun selectedText(): String {
        val range = selection ?: return ""
        return extractSelection(frame, range)
    }

    Box(
        modifier
            .background(panelBg)
            .onSizeChanged { viewportPx = it }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val copyChord = (event.isMetaPressed || event.isCtrlPressed) &&
                    event.key == Key.C &&
                    selection != null
                if (copyChord) {
                    val text = selectedText()
                    if (text.isNotEmpty()) {
                        copyText(text)
                        return@onPreviewKeyEvent true
                    }
                }
                if (event.key == Key.Escape && selection != null) {
                    selection = null
                    return@onPreviewKeyEvent true
                }
                if (readOnly) return@onPreviewKeyEvent false
                val bytes = encodeTerminalKey(event) ?: return@onPreviewKeyEvent false
                selection = null
                backend.write(bytes)
                true
            }
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Main) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val deltaY = change.scrollDelta.y
                if (deltaY == 0f) return@onPointerEvent
                val (col, row) = cellAt(change.position.x, change.position.y)
                val flags = backend.mouseFlags()
                val shift = event.keyboardModifiers.isShiftPressed
                val reportMouse = !readOnly && flags and RustMouseFlags.REPORTING != 0 && !shift
                if (reportMouse) {
                    if (wheel.onScroll(deltaY, flags, col, row)) change.consume()
                    return@onPointerEvent
                }
                // Local scrollback (Shift+wheel always local when mouse mode is on).
                localScrollAccum = (localScrollAccum + deltaY * 3f).coerceIn(-8f, 8f)
                val steps = localScrollAccum.toInt()
                if (steps != 0) {
                    localScrollAccum -= steps
                    // Positive Compose delta = wheel down → toward live edge (negative display Δ).
                    backend.scrollDisplay(-steps)
                    change.consume()
                }
            }
            .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Main) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                runCatching { focusRequester.requestFocus() }
                val (col, row) = cellAt(change.position.x, change.position.y)
                val flags = backend.mouseFlags()
                val shift = event.keyboardModifiers.isShiftPressed
                if (!readOnly && flags and RustMouseFlags.REPORTING != 0 && !shift) {
                    selection = null
                    RustTerminalMouse.encodeClick(
                        flags,
                        RustTerminalMouse.BUTTON_LEFT,
                        col,
                        row,
                        pressed = true,
                    )?.let { backend.write(it) }
                    change.consume()
                    return@onPointerEvent
                }
                selecting = true
                selection = CellRange(col, row, col, row)
                change.consume()
            }
            .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Main) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val (col, row) = cellAt(change.position.x, change.position.y)
                val flags = backend.mouseFlags()
                val shift = event.keyboardModifiers.isShiftPressed
                val dragging = event.buttons.isPrimaryPressed
                if (!readOnly && flags and RustMouseFlags.REPORTING != 0 && !shift) {
                    RustTerminalMouse.encodeMove(flags, col, row, dragging)?.let {
                        backend.write(it)
                        change.consume()
                    }
                    return@onPointerEvent
                }
                if (selecting && dragging) {
                    val start = selection ?: return@onPointerEvent
                    selection = start.copy(endCol = col, endRow = row)
                    change.consume()
                }
            }
            .onPointerEvent(PointerEventType.Release, pass = PointerEventPass.Main) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val (col, row) = cellAt(change.position.x, change.position.y)
                val flags = backend.mouseFlags()
                val shift = event.keyboardModifiers.isShiftPressed
                if (!readOnly && flags and RustMouseFlags.REPORTING != 0 && !shift) {
                    RustTerminalMouse.encodeClick(
                        flags,
                        RustTerminalMouse.BUTTON_LEFT,
                        col,
                        row,
                        pressed = false,
                    )?.let { backend.write(it) }
                    change.consume()
                    return@onPointerEvent
                }
                if (selecting) {
                    selecting = false
                    val range = selection
                    if (range != null && range.isEmpty()) {
                        selection = null
                    } else if (range != null) {
                        val text = extractSelection(frame, range)
                        if (text.isNotEmpty()) copyText(text)
                    }
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (tick >= 0L) {
                backend.copyPaintFrame(frame)
            }
            paintFrame(
                frame = frame,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                font = font,
                boldFont = boldFont,
                fallbackFonts = fallbackFonts,
                fontSizePx = fontSizePx,
                textPaint = textPaint,
                fallbackBg = panelBg,
                cursorColor = cursorColor,
                selection = selection,
                selectionBg = selectionBg,
                selectionFg = selectionFg,
            )
            // Tiny scrollback affordance when not at the live edge.
            if (frame.displayOffset > 0 && frame.historySize > 0) {
                val barH = size.height * (frame.rows.toFloat() / (frame.rows + frame.historySize).toFloat())
                    .coerceIn(0.08f, 1f)
                val travel = (size.height - barH).coerceAtLeast(0f)
                val top = travel * (1f - frame.displayOffset.toFloat() / frame.historySize.toFloat())
                    .coerceIn(0f, 1f)
                drawRect(
                    color = Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(size.width - 3f, top),
                    size = Size(2.5f, barH),
                )
            }
        }
    }
}

internal data class CellRange(
    val startCol: Int,
    val startRow: Int,
    val endCol: Int,
    val endRow: Int,
) {
    fun normalized(): CellRange {
        val a = startRow to startCol
        val b = endRow to endCol
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) {
            this
        } else {
            CellRange(endCol, endRow, startCol, startRow)
        }
    }

    fun isEmpty(): Boolean = startCol == endCol && startRow == endRow

    fun contains(col: Int, row: Int): Boolean {
        val n = normalized()
        val afterStart = row > n.startRow || (row == n.startRow && col >= n.startCol)
        val beforeEnd = row < n.endRow || (row == n.endRow && col <= n.endCol)
        return afterStart && beforeEnd
    }
}

internal fun extractSelection(frame: RustTerminalFrame, range: CellRange): String {
    if (frame.columns <= 0 || frame.rows <= 0) return ""
    val n = range.normalized()
    val startRow = n.startRow.coerceIn(0, frame.rows - 1)
    val endRow = n.endRow.coerceIn(0, frame.rows - 1)
    val sb = StringBuilder()
    for (row in startRow..endRow) {
        val c0 = if (row == startRow) n.startCol else 0
        val c1 = if (row == endRow) n.endCol else frame.columns - 1
        var line = StringBuilder()
        for (col in c0..c1.coerceAtLeast(c0)) {
            line.append(frame.cellChar(row, col))
        }
        sb.append(line.toString().trimEnd())
        if (row < endRow) sb.append('\n')
    }
    return sb.toString().trimEnd()
}

private fun DrawScope.paintFrame(
    frame: RustTerminalFrame,
    cellWidth: Float,
    cellHeight: Float,
    font: Font,
    boldFont: Font,
    fallbackFonts: MutableMap<Int, Font?>,
    fontSizePx: Float,
    textPaint: Paint,
    fallbackBg: Color,
    cursorColor: Color,
    selection: CellRange?,
    selectionBg: Color,
    selectionFg: Color,
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
            val selected = selection?.contains(col, row) == true
            if (selected) {
                bg = selectionBg.toArgbInt()
                fg = selectionFg.toArgbInt()
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
                val primary = if (useBold) boldFont else font
                val glyphFont = primary.takeIf { it.typeface?.getUTF32Glyph(ch.code) != 0.toShort() }
                    ?: fallbackFonts.getOrPut(ch.code) { resolveFallbackFont(ch.code, fontSizePx) }
                    ?: primary
                textPaint.color = fg
                native.drawString(
                    ch.toString(),
                    left,
                    top + cellHeight * 0.78f,
                    glyphFont,
                    textPaint,
                )
            }
        }
    }

    val cRow = frame.cursorRow
    val cCol = frame.cursorCol
    if (frame.displayOffset == 0 && cRow in 0 until rows && cCol in 0 until cols) {
        drawRect(
            color = cursorColor,
            topLeft = Offset(cCol * cellWidth, cRow * cellHeight),
            size = Size(cellWidth, cellHeight),
            alpha = 0.35f,
        )
    }
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun resolveTypeface(family: TerminalFontFamily): Typeface {
    val candidates = listOfNotNull(family.awtName, "Menlo", "SF Mono", "Monaco", "monospace")
    for (name in candidates) {
        FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL)?.let { return it }
    }
    return Typeface.makeEmpty()
}

/**
 * Looks up a system font that can render [codepoint] when the primary monospace face can't
 * (Nerd Font/powerline prompt icons, box-drawing, emoji). Returns null if nothing covers it,
 * in which case the caller keeps rendering with the primary font's tofu glyph.
 */
private fun resolveFallbackFont(codepoint: Int, fontSizePx: Float): Font? {
    val face = FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, codepoint)
        ?: return null
    return Font(face, fontSizePx)
}
