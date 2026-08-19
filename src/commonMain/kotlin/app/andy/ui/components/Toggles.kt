package app.andy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border

private val SwitchWidth = 36.dp
private val SwitchHeight = 22.dp
private val SwitchThumb = 18.dp
private val SwitchInset = 2.dp
private val CheckboxSize = 16.dp
private val CheckboxCorner = 4.dp

/**
 * Compact macOS/iOS-style pill switch. Accent fill when on; quiet track when off.
 */
@Composable
internal fun AndySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "andy-switch",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> AndyColors.Orange.copy(alpha = 0.38f)
            !enabled -> AndyColors.SurfaceHover.copy(alpha = 0.55f)
            checked -> AndyColors.Orange
            else -> AndyColors.Neutral500.copy(alpha = if (AndyColors.isLight) 0.28f else 0.42f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "andy-switch-track",
    )
    val travel = SwitchWidth - SwitchThumb - SwitchInset * 2
    val thumbModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .then(thumbModifier)
            .size(SwitchWidth, SwitchHeight)
            .clip(RoundedCornerShape(AndyRadius.Pill))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = SwitchInset + travel * fraction)
                .size(SwitchThumb)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.72f)),
        )
    }
}

/**
 * Compact native checkbox: thin rounded square, accent fill and a hairline check when on.
 */
@Composable
internal fun AndyCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val fill by animateColorAsState(
        targetValue = when {
            !checked -> Color.Transparent
            !enabled -> AndyColors.Orange.copy(alpha = 0.38f)
            else -> AndyColors.Orange
        },
        animationSpec = tween(durationMillis = 120),
        label = "andy-checkbox-fill",
    )
    val stroke = when {
        checked -> Color.Transparent
        enabled -> Border
        else -> AndyColors.TextDisabled
    }
    val checkColor = if (enabled) {
        if (AndyColors.isLight) Color.White else Color(0xFF0A0A0A)
    } else {
        Color.White.copy(alpha = 0.7f)
    }
    val boxModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .then(boxModifier)
            .size(CheckboxSize)
            .clip(RoundedCornerShape(CheckboxCorner))
            .background(fill)
            .border(1.dp, stroke, RoundedCornerShape(CheckboxCorner))
            .drawBehind {
                if (!checked) return@drawBehind
                val path = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.72f)
                    lineTo(size.width * 0.78f, size.height * 0.28f)
                }
                drawPath(
                    path = path,
                    color = checkColor,
                    style = Stroke(width = size.width * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            },
    )
}
