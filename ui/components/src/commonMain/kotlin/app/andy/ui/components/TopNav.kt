package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Astryx TopNav — slot layout: heading | startContent … endContent.
 *
 * Visual markers: padding Space2, left gap Space4, start/end gaps Space1.
 */
@Composable
fun TopNav(
    modifier: Modifier = Modifier,
    heading: @Composable () -> Unit = {},
    startContent: @Composable RowScope.() -> Unit = {},
    endContent: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = AndySpace.Space2, end = AndySpace.Space2, top = AndySpace.Space2, bottom = AndySpace.Space1),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space4),
        ) {
            Box(Modifier.widthIn(max = 220.dp)) { heading() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
                content = startContent,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
            content = endContent,
        )
    }
}

/** Astryx TopNavHeading — large title + optional supporting subtitle (static, non-interactive). */
@Composable
fun TopNavHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier.padding(horizontal = AndySpace.Space1),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            title,
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                color = AndyColors.TextTertiary,
                fontFamily = DisplayFont,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Astryx TopNavItem — element radius, inline/block padding, overlay hover, selected fill.
 */
@Composable
fun TopNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    content: @Composable () -> Unit = {
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontFamily = DisplayFont,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> AndyColors.SurfaceSelected
        hovered -> AndyColors.SurfaceHover
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Box(
        modifier
            .heightIn(min = 32.dp)
            .clip(AndyShape.Interactive)
            .background(background, AndyShape.Interactive)
            .semantics {
                role = Role.Button
                this.selected = selected
                contentDescription?.let { this.contentDescription = it }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = AndySpace.Space3, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
