package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun WorkspaceSplit(
    sidebarWidth: Dp = AndyLayout.ListWidth,
    modifier: Modifier = Modifier,
    sidebar: @Composable ColumnScope.() -> Unit,
    main: @Composable BoxScope.() -> Unit,
) {
    Row(
        modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        WorkspaceRail(
            Modifier.width(sidebarWidth).fillMaxHeight(),
            content = sidebar,
        )
        WorkspaceCanvas(
            Modifier.weight(1f).fillMaxHeight(),
            content = main,
        )
    }
}

@Composable
internal fun WorkspaceRail(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .rightBorder(Border.copy(alpha = 0.28f))
            .padding(end = AndySpace.Space4),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
        content = content,
    )
}

@Composable
internal fun WorkspaceRailHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

@Composable
internal fun WorkspaceCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .padding(start = AndySpace.Space5),
        content = content,
    )
}

@Composable
internal fun WorkspaceEmptyCanvas(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = TextSecondary,
            fontFamily = DisplayFont,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = AndySpace.Space8),
        )
    }
}

@Composable
internal fun WorkspaceItemRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indented: Boolean = false,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        selected -> AndyColors.SurfaceSelected
        hovered -> AndyColors.SurfaceHover
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (indented) AndySpace.Space4 else 0.dp)
            .background(background, RoundedCornerShape(AndyRadius.Row))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = AndySpace.Space4, vertical = if (subtitle == null) 10.dp else AndySpace.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        leading?.invoke()
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                color = if (selected) TextPrimary else TextSecondary,
                fontFamily = DisplayFont,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = AndyColors.TextTertiary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Box(Modifier.alpha(if (selected || hovered) 1f else 0.55f)) { it() }
        }
    }
}

@Composable
internal fun WorkspaceSectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            color = TextSecondary.copy(alpha = 0.82f),
            fontFamily = MonoFont,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
        if (count != null) {
            Text(
                count.toString(),
                color = TextSecondary.copy(alpha = 0.55f),
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
    }
}
