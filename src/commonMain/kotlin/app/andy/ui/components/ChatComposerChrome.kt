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
import androidx.compose.foundation.layout.offset
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
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Chat input shell: elevated surface + hairline border (Design DNA composer).
 * Stronger ring only while dragging images / focus-highlight.
 */
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
    val borderColor = if (highlighted) {
        Cyan.copy(alpha = 0.42f)
    } else {
        Border
    }
    Column(
        modifier
            .clip(shape)
            .background(AndyColors.SurfaceRaised, shape)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
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
        selected -> TextSecondary
        else -> TextSecondary.copy(alpha = 0.70f)
    }
    // DNA: controls sit on the elevated composer; hover/active are barely lighter fills.
    val container = when {
        !enabled -> Color.Transparent
        selected -> Color.White.copy(alpha = if (AndyColors.isLight) 0.08f else 0.07f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .height(AndyLayout.ControlHeightSm)
            .clip(RoundedCornerShape(AndyRadius.Control))
            .background(container, RoundedCornerShape(AndyRadius.Control))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingContent?.invoke()
        Text(
            text,
            color = contentColor,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showChevron) {
            // ⌄ sits optically low in its em-box; pin to a square and nudge up.
            Box(
                Modifier.size(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⌄",
                    color = contentColor.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    modifier = Modifier.offset(y = (-1).dp),
                )
            }
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
        modifier
            .fillMaxWidth()
            .padding(top = AndySpace.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
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
    focusHint: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            color = if (highlighted) Cyan else AndyColors.TextDisabled,
            fontFamily = DisplayFont,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f).padding(end = AndySpace.Space3),
        )
        if (focusHint != null) {
            Text(
                focusHint,
                color = AndyColors.TextDisabled,
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
