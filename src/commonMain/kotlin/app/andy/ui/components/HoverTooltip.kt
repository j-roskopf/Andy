package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.TextPrimary
import kotlinx.coroutines.delay

/** Hover dwell before a tooltip appears. Long enough that passing over a control stays quiet. */
internal const val HoverTooltipDelayMillis = 1_200L

/**
 * Label that appears after hovering [content] for [delayMillis].
 *
 * Hand-rolled rather than Material3's `TooltipBox`, which has no show-delay, or foundation's
 * `TooltipArea`, which has one but is desktop-only — this is common code shared with the web
 * target. The popup is non-focusable so it cannot steal focus from the composer's text field.
 */
@Composable
internal fun HoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Long = HoverTooltipDelayMillis,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var visible by remember { mutableStateOf(false) }
    // Keyed on the text too: a label that changes while shown (e.g. the control was toggled)
    // restarts the dwell rather than leaving a stale string on screen.
    LaunchedEffect(hovered, text) {
        if (!hovered) {
            visible = false
            return@LaunchedEffect
        }
        visible = false
        delay(delayMillis)
        visible = true
    }
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    Box(modifier.hoverable(interactionSource)) {
        content()
        if (visible) {
            Popup(
                popupPositionProvider = remember(gapPx) { AboveAnchorPositionProvider(gapPx) },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier
                        .background(PanelSoft, RoundedCornerShape(AndyRadius.Control))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(text, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Centres the tooltip above the anchor, flipping below and clamping when there is no room. */
private class AboveAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = if (above >= 0) above else anchorBounds.bottom + gapPx
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), y)
    }
}
