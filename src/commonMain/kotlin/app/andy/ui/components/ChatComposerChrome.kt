package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/** Chat input shell: raised background only. Border appears solely while dragging images. */
@Composable
internal fun ChatComposerFrame(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = AndySpace.Space4,
        vertical = AndySpace.Space3,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = AndyShape.Sheet
    Column(
        modifier
            .clip(shape)
            .background(AndyColors.SurfaceRaised, shape)
            .then(
                if (highlighted) Modifier.border(1.dp, Cyan.copy(alpha = 0.55f), shape)
                else Modifier,
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
        content = content,
    )
}

/** Quiet ghost chip for the composer toolbar (model, effort, access). */
@Composable
internal fun ComposerChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
) {
    val contentColor = when {
        !enabled -> AndyColors.TextDisabled
        selected -> TextPrimary
        else -> TextSecondary.copy(alpha = 0.70f)
    }
    // Chips sit on SurfaceRaised; tonal selected tokens are too close, so use a
    // higher-contrast fill that still stays borderless.
    val container = when {
        !enabled -> Color.Transparent
        selected -> if (AndyColors.isLight) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.14f)
        }
        else -> Color.Transparent
    }
    Row(
        modifier
            .height(AndyLayout.ControlHeightSm)
            .clip(AndyShape.Interactive)
            .background(container, AndyShape.Interactive)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AndySpace.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        leadingContent?.invoke()
        Text(
            text,
            color = contentColor,
            fontFamily = DisplayFont,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showChevron) {
            Text(
                "⌄",
                color = contentColor.copy(alpha = if (selected) 0.70f else 0.50f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
            )
        }
    }
}

@Composable
internal fun ComposerToolbarRow(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            content = leading,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
            content = trailing,
        )
    }
}

@Composable
internal fun ComposerPlaceholderHint(
    text: String,
    highlighted: Boolean = false,
    focusHint: String? = "⌘L to focus",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            color = if (highlighted) Cyan else TextSecondary.copy(alpha = 0.72f),
            fontFamily = DisplayFont,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f).padding(end = AndySpace.Space3),
        )
        if (focusHint != null) {
            Text(
                focusHint,
                color = TextSecondary.copy(alpha = 0.42f),
                fontFamily = DisplayFont,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
internal fun ComposerStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(AndyRadius.Pill)),
    )
}
