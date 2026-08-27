package app.andy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

enum class DropdownMenuItemVariant {
    Default,
    Destructive,
}

/**
 * Astryx DropdownMenu — popover menu anchored to a trigger with overlay item hover.
 */
@Composable
fun AndyDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) {
        SuppressHeavyweightSurfacesWhileOpen()
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = 180.dp)
            .heightIn(max = 300.dp),
        containerColor = AndyColors.SurfacePopover,
        shape = AndyShape.Sheet,
        border = BorderStroke(1.dp, Border),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(AndySpace.Space1),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
fun AndyDropdownMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    variant: DropdownMenuItemVariant = DropdownMenuItemVariant.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val labelColor = when {
        !enabled -> TextSecondary
        variant == DropdownMenuItemVariant.Destructive -> Red
        else -> TextPrimary
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(AndyShape.Interactive)
            .background(if (hovered && enabled) AndyColors.SurfaceHover else androidx.compose.ui.graphics.Color.Transparent)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = labelColor,
                fontFamily = DisplayFont,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    description,
                    color = if (variant == DropdownMenuItemVariant.Destructive) Red.copy(alpha = 0.8f) else TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun AndyDropdownMenuSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = TextSecondary,
        fontFamily = MonoFont,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(
            horizontal = AndySpace.Space2,
            vertical = AndySpace.Space1,
        ),
    )
}

/** Ghost trigger with optional prefix and chevron — Astryx DropdownMenu button slot. */
@Composable
fun AndyDropdownTrigger(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prefix: @Composable (RowScope.() -> Unit)? = null,
    contentDescription: String? = null,
) {
    GhostButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minWidth = 120.dp)
            .widthIn(max = 240.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        if (prefix != null) prefix()
        Text(
            label,
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            if (expanded) "▴" else "▾",
            color = TextSecondary,
            fontFamily = DisplayFont,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = AndySpace.Space1),
        )
    }
}
