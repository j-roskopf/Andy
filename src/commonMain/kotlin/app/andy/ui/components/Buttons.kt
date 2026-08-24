package app.andy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.andyPressScale
import app.andy.ui.theme.andyTokens

/** Primary filled CTA — Astryx `variant="primary"`. */
@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AndyShape.Interactive,
    colors: ButtonColors = primaryButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = AndySpace.Space3, vertical = 0.dp),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier
            .height(AndyLayout.ControlHeightMd)
            .defaultMinSize(minHeight = AndyLayout.ControlHeightMd, minWidth = 1.dp)
            .andyPressScale(interactionSource, enabled),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** Secondary filled action — Astryx `variant="secondary"`. */
@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fixedHeight: Boolean = true,
    shape: Shape = AndyShape.Interactive,
    colors: ButtonColors = secondaryButtonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = AndySpace.Space3, vertical = 0.dp),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    @Suppress("UNUSED_PARAMETER")
    val retainedBorderForCallSiteCompatibility = border
    val heightModifier = if (fixedHeight) {
        Modifier
            .height(AndyLayout.ControlHeightMd)
            .defaultMinSize(minHeight = AndyLayout.ControlHeightMd, minWidth = 1.dp)
    } else {
        Modifier
            .defaultMinSize(minHeight = AndyLayout.ControlHeightMd)
            .heightIn(min = AndyLayout.ControlHeightMd)
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier
            .then(heightModifier)
            .andyPressScale(interactionSource, enabled),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** Quiet text action — no container, accent or primary text on hover semantics via M3. */
@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AndyShape.Interactive,
    colors: ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
        disabledContentColor = AndyColors.TextDisabled,
    ),
    contentPadding: PaddingValues = PaddingValues(horizontal = AndySpace.Space3, vertical = 0.dp),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier
            .height(AndyLayout.ControlHeightMd)
            .defaultMinSize(minHeight = AndyLayout.ControlHeightMd, minWidth = 1.dp)
            .andyPressScale(interactionSource, enabled),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** Transparent ghost — hover fill only, for toolbars and secondary chrome. */
@Composable
internal fun GhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AndyShape.Interactive,
    contentPadding: PaddingValues = PaddingValues(horizontal = AndySpace.Space3, vertical = AndySpace.Space2),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(
                minWidth = AndyLayout.IconHitArea,
                minHeight = AndyLayout.IconHitArea,
            )
            .andyPressScale(interactionSource, enabled),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = AndyColors.TextDisabled,
        ),
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/** Square icon-only control — Astryx IconButton (ghost, md = 32dp). */
@Composable
internal fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AndyShape.Interactive,
    contentDescription: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier
            .size(AndyLayout.ControlHeightMd)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .andyPressScale(interactionSource, enabled),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = AndyColors.TextDisabled,
        ),
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource,
    ) {
        Box(Modifier.size(AndyLayout.IconMd), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
internal fun primaryButtonColors(): ButtonColors {
    val tokens = andyTokens()
    return ButtonDefaults.buttonColors(
        containerColor = tokens.accent,
        contentColor = tokens.onAccent,
        disabledContainerColor = AndyColors.SurfaceHover,
        disabledContentColor = AndyColors.TextDisabled,
    )
}

@Composable
internal fun secondaryButtonColors(): ButtonColors {
    val tokens = andyTokens()
    return ButtonDefaults.buttonColors(
        containerColor = tokens.neutralFill,
        contentColor = TextPrimary,
        disabledContainerColor = AndyColors.PaneBg,
        disabledContentColor = AndyColors.TextDisabled,
    )
}

@Composable
internal fun destructiveButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError,
    disabledContainerColor = AndyColors.SurfaceHover,
    disabledContentColor = AndyColors.TextDisabled,
)

@Composable
internal fun dangerOutlinedButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.error,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = AndyColors.TextDisabled,
)

@Composable
internal fun accentTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
    contentColor = MaterialTheme.colorScheme.primary,
    disabledContentColor = AndyColors.TextDisabled,
)

@Composable
internal fun mutedTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
    contentColor = TextPrimary,
    disabledContentColor = AndyColors.TextDisabled,
)

/** High-contrast CTA on dark overlays (import sheets, etc.). */
@Composable
internal fun contrastPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = TextPrimary,
    contentColor = AndyColors.WindowBg,
    disabledContainerColor = AndyColors.SurfaceHover,
    disabledContentColor = AndyColors.TextDisabled,
)

/** Outlined control on dark hardware overlay panels. */
@Composable
internal fun overlayOutlinedButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    containerColor = AndyColors.Neutral900.copy(alpha = 0.42f),
    contentColor = TextPrimary,
    disabledContentColor = AndyColors.TextDisabled,
)

/** Segmented/on-off primary control (e.g. live logcat toggle). */
@Composable
internal fun togglePrimaryButtonColors(active: Boolean): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = if (active) MaterialTheme.colorScheme.primary else AndyColors.SurfaceRaised,
    contentColor = if (active) andyTokens().onAccent else TextPrimary,
    disabledContainerColor = AndyColors.SurfaceHover,
    disabledContentColor = AndyColors.TextDisabled,
)

