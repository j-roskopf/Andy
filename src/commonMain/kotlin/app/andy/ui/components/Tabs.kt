package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Underline-style tab bar for page-level navigation. Prefer this over [FilterPill]
 * when switching between distinct content panes.
 *
 * [trailing] is placed on the trailing edge of the tab row (e.g. filter pills).
 */
@Composable
internal fun TabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space5),
                verticalAlignment = Alignment.Bottom,
            ) {
                tabs.forEachIndexed { index, label ->
                    TabBarItem(
                        label = label,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        // Match TabBarItem bottom inset so trailing content cannot grow the bar.
                        .padding(bottom = AndySpace.Space2)
                        .height(28.dp)
                        .horizontalScroll(rememberScrollState()),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    trailing()
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Border),
        )
    }
}

@Composable
internal fun <T> TabBar(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    TabBar(
        tabs = tabs.map(label),
        selectedIndex = tabs.indexOf(selected).coerceAtLeast(0),
        onSelect = { index -> onSelect(tabs[index]) },
        modifier = modifier,
        trailing = trailing,
    )
}

@Composable
private fun TabBarItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val textColor = when {
        selected -> TextPrimary
        hovered -> TextPrimary.copy(alpha = 0.82f)
        else -> TextSecondary
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(AndyRadius.Control))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(bottom = AndySpace.Space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = textColor,
            fontFamily = DisplayFont,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .height(2.dp)
                .width(if (selected) 28.dp else 0.dp)
                .background(if (selected) Rust else Color.Transparent, RoundedCornerShape(AndyRadius.Pill)),
        )
    }
}

/**
 * Compact segmented control for mutually exclusive options within a section
 * (e.g. stream quality presets).
 */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = AndyShape.Interactive
    Row(
        modifier
            .clip(shape)
            .background(AndyColors.SurfaceHover, shape),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Text(
                label,
                color = if (selected) TextPrimary else TextSecondary,
                fontFamily = DisplayFont,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) AndyColors.SurfaceSelected else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
