package app.andy.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import kotlinx.coroutines.delay

/** Hover dwell before a tooltip appears. */
internal const val TooltipDelayMillis = 1_200L

/** Astryx Tooltip — inverted surface, container radius, body label type. */
@Composable
internal fun Tooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Long = TooltipDelayMillis,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var visible by remember { mutableStateOf(false) }
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
    val shape = RoundedCornerShape(AndyRadius.Menu)
    val tooltipBackground = if (AndyColors.isLight) Color(0xFF111112) else Color(0xFFF1F4F7)
    val tooltipForeground = if (AndyColors.isLight) Color(0xFFF1F4F7) else Color(0xFF111112)
    Box(modifier.hoverable(interactionSource)) {
        content()
        if (visible) {
            Popup(
                popupPositionProvider = remember(gapPx) { TooltipAboveAnchorPositionProvider(gapPx) },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier
                        .shadow(2.dp, shape, clip = false)
                        .background(tooltipBackground, shape)
                        .padding(
                            horizontal = AndySpace.Space2,
                            vertical = AndySpace.Space1,
                        ),
                ) {
                    Text(
                        text,
                        color = tooltipForeground,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

private class TooltipAboveAnchorPositionProvider(private val gapPx: Int) : PopupPositionProvider {
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
