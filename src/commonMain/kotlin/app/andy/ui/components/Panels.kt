package app.andy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.AndyStroke
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow

internal fun Modifier.rightBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = AndyStroke.Hairline.toPx()
    val x = size.width - strokeWidth / 2f
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
}

internal fun Modifier.bottomBorder(color: Color): Modifier = drawBehind {
    val strokeWidth = AndyStroke.Hairline.toPx()
    val y = size.height - strokeWidth / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
}

internal fun Modifier.noiseGridOverlay(alpha: Float = 0.07f): Modifier = drawBehind {
    val grid = 18.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(AndyColors.Neutral100.copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), 1f)
        x += grid
    }
    var y = 0f
    while (y < size.height) {
        drawLine(AndyColors.Neutral100.copy(alpha = alpha * 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
        y += grid
    }
}

@Composable
internal fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = TextSecondary,
            fontFamily = DisplayFont,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            value,
            color = if (ok) Green else Rust,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

@Composable
internal fun StatusTag(
    label: String,
    variant: StatusDotVariant,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    accentColor: Color? = null,
) {
    val color = accentColor ?: statusTagColor(variant)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.heightIn(min = AndyLayout.ControlHeightXs)
                .background(color.copy(alpha = 0.12f), AndyShape.Interactive)
                .padding(horizontal = AndySpace.Space3, vertical = AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            StatusDot(variant = variant, pulsing = pulsing)
            Text(
                label,
                color = color,
                fontFamily = DisplayFont,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun StatusTag(label: String, color: Color, modifier: Modifier = Modifier) {
    StatusTag(
        label = label,
        variant = statusDotVariantForColor(color),
        modifier = modifier,
        accentColor = color,
    )
}

private fun statusTagColor(variant: StatusDotVariant): Color = when (variant) {
    StatusDotVariant.Success -> Green
    StatusDotVariant.Warning -> Rust
    StatusDotVariant.Error -> Red
    StatusDotVariant.Info -> Cyan
    StatusDotVariant.Neutral -> TextSecondary
}

private fun statusDotVariantForColor(color: Color): StatusDotVariant = when (color) {
    Green -> StatusDotVariant.Success
    Rust, Yellow -> StatusDotVariant.Warning
    Red -> StatusDotVariant.Error
    Cyan -> StatusDotVariant.Info
    else -> StatusDotVariant.Neutral
}

@Composable
internal fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$name subsystem is represented in navigation and service contracts for v1 expansion.", color = TextSecondary)
    }
}

@Composable
internal fun Toolbar(
    @Suppress("UNUSED_PARAMETER") title: String,
    @Suppress("UNUSED_PARAMETER") subtitle: String,
    onPrimary: (() -> Unit)? = null,
    primaryLabel: String = "Run",
    primaryEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Page title/subtitle chrome lives in the global TopChrome; keep only trailing actions here.
    if (onPrimary == null) return
    Row(
        modifier.fillMaxWidth().padding(bottom = AndySpace.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            colors = primaryButtonColors(),
            shape = RoundedCornerShape(AndyRadius.Row),
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
        ) { Text(primaryLabel, fontFamily = DisplayFont, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    background: Color = AndyColors.SurfaceRaised,
    accent: Color? = null,
    borderColor: Color? = null,
    /** Boxy chrome (composer, header) should pass [AndyShape.Interactive]; cards/menus use [AndyShape.Menu]. */
    shape: Shape = AndyShape.Menu,
    contentPadding: PaddingValues = PaddingValues(AndySpace.Space5),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AndySpace.Space4),
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedBorder = borderColor ?: accent?.copy(alpha = 0.35f)
    val variant = when (background) {
        Color.Transparent -> CardVariant.Transparent
        else -> CardVariant.Default
    }
    Card(
        modifier = modifier,
        variant = variant,
        shape = shape,
        backgroundColor = background,
        borderColor = resolvedBorder ?: Color.Transparent,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
internal fun FilterPill(
    text: String,
    selected: Boolean,
    @Suppress("UNUSED_PARAMETER") color: Color,
    enabled: Boolean = true,
    toolbar: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = AndyShape.Interactive
    val containerColor = when {
        !enabled -> AndyColors.PaneBg
        selected -> AndyColors.SurfaceSelected
        else -> Color.Transparent
    }
    val borderColor = when {
        !enabled -> Color.Transparent
        selected -> Border.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> AndyColors.TextDisabled
        selected -> TextPrimary
        else -> TextSecondary
    }
    if (toolbar) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor,
                disabledContentColor = contentColor,
            ),
            border = BorderStroke(1.dp, borderColor),
            contentPadding = PaddingValues(horizontal = AndySpace.Space4, vertical = AndySpace.Space2),
        ) {
            Row(
                Modifier.alpha(if (enabled) 1f else 0.55f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            ) {
                leadingContent?.invoke()
                Text(
                    text,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        }
        return
    }
    Box(
        Modifier
            .height(AndyLayout.ControlHeightSm)
            .background(containerColor, shape)
            .then(
                if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape)
                else Modifier,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.alpha(if (enabled) 1f else 0.55f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            leadingContent?.invoke()
            Text(
                text,
                color = contentColor,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
internal fun ControlRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontFamily = DisplayFont)
        Text(value, color = TextPrimary, fontFamily = MonoFont)
    }
}

@Composable
internal fun MetricCard(label: String, value: String) {
    PanelCard(Modifier.width(170.dp).height(96.dp)) {
        Text(label, color = TextSecondary, fontFamily = DisplayFont, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 24.sp, fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold)
    }
}
