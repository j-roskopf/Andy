package app.andy.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.andy.formatDecimal
import app.andy.model.AgentEvent
import app.andy.model.AgentTask
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow

/** Live token usage for the active conversation, sourced from the most recent [AgentEvent.ContextUsage]. */
internal data class AgentContextWindowStatus(val usedTokens: Long, val capacityTokens: Long?) {
    val fraction: Float? = capacityTokens?.takeIf { it > 0 }?.let { (usedTokens.toFloat() / it).coerceIn(0f, 1f) }
}

/** Prefers the live usage snapshot streamed by the provider; falls back to whatever the task last persisted. */
internal fun agentContextWindowStatus(task: AgentTask, events: List<AgentEvent>): AgentContextWindowStatus? {
    events.asReversed().forEach { event ->
        if (event is AgentEvent.ContextUsage) {
            val used = event.usedTokens ?: task.contextTokens ?: task.inputTokens
            if (used != null) {
                return AgentContextWindowStatus(used, event.windowTokens?.takeIf { it > 0 } ?: task.contextWindowTokens)
            }
        }
    }
    val used = task.contextTokens ?: task.inputTokens ?: return null
    return AgentContextWindowStatus(used, task.contextWindowTokens?.takeIf { it > 0 })
}

private fun agentContextWindowStatusColor(status: AgentContextWindowStatus): Color = when (val fraction = status.fraction) {
    null -> TextSecondary
    else -> when {
        fraction >= 0.9f -> Red
        fraction >= 0.75f -> Yellow
        else -> Cyan
    }
}

internal fun agentContextUsageSummary(status: AgentContextWindowStatus): String {
    val used = formatCompactTokenCount(status.usedTokens)
    val capacity = status.capacityTokens ?: return "$used context used · limit not reported"
    val percent = status.usedTokens.toDouble() / capacity * 100.0
    return "${formatDecimal(percent, 1)}% · $used/${formatCompactTokenCount(capacity)} context used"
}

/** Compact ring gauge for the chat composer; hovering reveals a Context window breakdown, mirroring the indicator in tools like Synara. */
@Composable
internal fun AgentContextUsageIndicator(
    status: AgentContextWindowStatus?,
    modifier: Modifier = Modifier,
) {
    if (status == null) return
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var popupHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val color = agentContextWindowStatusColor(status)

    Box(
        modifier
            .size(28.dp)
            .hoverable(interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        ContextRingGlyph(status.fraction, color, diameter = 18.dp)

        if (hovered) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, -(popupHeightPx + with(density) { 10.dp.roundToPx() })),
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    modifier = Modifier
                        .onGloballyPositioned { popupHeightPx = it.size.height }
                        .width(252.dp)
                        .background(Panel, RoundedCornerShape(AndyRadius.Control))
                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.Control))
                        .padding(AndySpace.Space4),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Context window",
                        color = TextPrimary,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    Text(agentContextUsageSummary(status), color = color, fontFamily = MonoFont, fontSize = 11.sp)
                    status.capacityTokens?.let { capacity ->
                        Text(
                            "Model window: ${formatCompactTokenCount(capacity)} tokens",
                            color = TextSecondary,
                            fontFamily = MonoFont,
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        "Context is managed automatically as this conversation grows.",
                        color = TextSecondary.copy(alpha = 0.82f),
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextRingGlyph(fraction: Float?, color: Color, diameter: Dp) {
    Canvas(Modifier.size(diameter)) {
        val stroke = Stroke(width = size.minDimension * 0.18f, cap = StrokeCap.Round)
        val inset = stroke.width / 2f
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(
            color = Border,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        val sweep = (fraction ?: 0f) * 360f
        if (sweep > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
        }
    }
}
