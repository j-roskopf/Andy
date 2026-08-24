package app.andy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.andyTokens

private val SwitchWidth = 40.dp
private val SwitchHeight = 24.dp
private val SwitchThumbOff = 16.dp
private val SwitchThumbOn = 20.dp
private val SwitchInset = 4.dp
private val CheckboxSize = 16.dp
private val CheckboxCorner = 4.dp

/** Astryx Switch — md 40×24, accent track when on, skeleton track when off. */
@Composable
internal fun AndySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = andyTokens()
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = AndyMotion.standardTween(AndyMotion.StandardMs),
        label = "andy-switch",
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled && checked -> tokens.accent.copy(alpha = 0.38f)
            !enabled -> tokens.skeleton.copy(alpha = 0.55f)
            checked -> tokens.accent
            else -> tokens.skeleton
        },
        animationSpec = AndyMotion.standardTween(AndyMotion.StandardMs),
        label = "andy-switch-track",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (checked) SwitchThumbOn else SwitchThumbOff,
        animationSpec = AndyMotion.standardTween(AndyMotion.StandardMs),
        label = "andy-switch-thumb-size",
    )
    val travel = SwitchWidth - SwitchThumbOn - SwitchInset * 2
    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled && !loading,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier
            .then(toggleModifier)
            .size(SwitchWidth, SwitchHeight)
            .clip(RoundedCornerShape(AndyRadius.Pill))
            .background(trackColor),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (loading) {
            Spinner(
                spinnerSize = SpinnerSize.Sm,
                shade = if (checked) SpinnerShade.OnMedia else SpinnerShade.Subtle,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Box(
                Modifier
                    .offset(x = SwitchInset + travel * fraction + (SwitchThumbOn - thumbSize) / 2)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.72f)),
            )
        }
    }
}

/** Astryx CheckboxInput indicator — 16dp, accent fill, emphasized border. */
@Composable
internal fun AndyCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val tokens = andyTokens()
    val fill by animateColorAsState(
        targetValue = when {
            !checked -> Color.Transparent
            !enabled -> tokens.accent.copy(alpha = 0.38f)
            else -> tokens.accent
        },
        animationSpec = AndyMotion.standardTween(AndyMotion.FastMs),
        label = "andy-checkbox-fill",
    )
    val stroke = when {
        checked -> Color.Transparent
        enabled -> AndyColors.BorderEmphasized
        else -> AndyColors.TextDisabled
    }
    val checkColor = Color.White.copy(alpha = if (enabled) 1f else 0.7f)
    val boxModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled && !loading,
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
            .border(1.dp, stroke, RoundedCornerShape(CheckboxCorner)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Spinner(spinnerSize = SpinnerSize.Sm)
            checked -> Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
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
    }
}
