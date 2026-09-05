package app.andy.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.components.bottomBorder
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Which top-chrome flyout is open. In-layout under the toolbar so Live/Browser reflow —
 * no Popup layer, so heavyweight occlusion is unnecessary for these menus.
 */
internal enum class ChromeFlyoutKind {
    LocalServers,
    DockLanding,
    DevicePicker,
    ActionProjectPicker,
    ActionPicker,
}

private val ChromeFlyoutEnterMillis = 220
private val ChromeFlyoutExitMillis = 180

/**
 * Positions the compact, anchored content column inside the full-width reflowing surface.
 * The clamp keeps a toolbar or tab-strip trigger near either edge from creating a tiny menu.
 */
internal fun chromeFlyoutContentStart(
    anchorX: Dp,
    contentWidth: Dp,
    hostWidth: Dp,
    contentAnchorInset: Dp,
): Dp {
    val minimumStart = AndySpace.Space5
    val maximumStart = (hostWidth - contentWidth - AndySpace.Space5)
        .coerceAtLeast(minimumStart)
    return (anchorX - contentAnchorInset).coerceIn(minimumStart, maximumStart)
}

/**
 * Expands under the toolbar (or dock tab strip) in the normal layout tree. Content below
 * reflows; SwingPanel / Metal / WKWebView peers never need to leave composition for the menu.
 *
 * Callers must keep [content] stable while [visible] becomes false so the exit animation
 * still has something to measure (hold the last open kind until the next open).
 */
@Composable
internal fun ChromeFlyout(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** X coordinate of the trigger label/icon in root pixels. */
    anchorXInRoot: Float? = null,
    /** Keep an anchored list usable when its trigger sits near a host's edge. */
    preferredContentWidth: Dp? = null,
    /** The distance from the content edge to the visual point that should meet [anchorXInRoot]. */
    contentAnchorInset: Dp = AndySpace.Space2,
    /**
     * When the same flyout host swaps panels without closing, keying scroll on the
     * active content prevents the new panel from opening mid-list.
     */
    contentKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(ChromeFlyoutEnterMillis)) +
            expandVertically(
                animationSpec = tween(ChromeFlyoutEnterMillis),
                expandFrom = Alignment.Top,
            ),
        exit = fadeOut(animationSpec = tween(ChromeFlyoutExitMillis)) +
            shrinkVertically(
                animationSpec = tween(ChromeFlyoutExitMillis),
                shrinkTowards = Alignment.Top,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        val scrollState = remember(contentKey) { ScrollState(initial = 0) }
        var flyoutLeftInRoot by remember { mutableStateOf<Float?>(null) }
        Column(
            Modifier
                .fillMaxWidth()
                .background(AndyColors.SurfaceRaised)
                .bottomBorder(PaneDividerTint)
                .heightIn(max = 320.dp)
                .verticalScroll(scrollState),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = AndySpace.Space3)
                    .onGloballyPositioned { coordinates ->
                        val next = coordinates.positionInRoot().x
                        if (flyoutLeftInRoot != next) flyoutLeftInRoot = next
                    },
            ) {
                val availableWidth = (maxWidth - AndySpace.Space5 * 2).coerceAtLeast(0.dp)
                val anchoredWidth = preferredContentWidth
                    ?.coerceAtMost(availableWidth)
                    ?.takeIf { anchorXInRoot != null && flyoutLeftInRoot != null }
                val contentModifier = if (anchoredWidth == null) {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AndySpace.Space5)
                } else {
                    val anchorX = with(LocalDensity.current) {
                        (anchorXInRoot!! - flyoutLeftInRoot!!).toDp()
                    }
                    val start = chromeFlyoutContentStart(
                        anchorX = anchorX,
                        contentWidth = anchoredWidth,
                        hostWidth = maxWidth,
                        contentAnchorInset = contentAnchorInset,
                    )
                    Modifier
                        .width(anchoredWidth)
                        .offset(x = start)
                }
                Column(contentModifier, content = content)
            }
        }
    }
}

@Composable
internal fun ChromeFlyoutSectionLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontFamily = DisplayFont,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            start = AndySpace.Space2,
            top = AndySpace.Space2,
            bottom = AndySpace.Space1,
        ),
    )
}

@Composable
internal fun ChromeFlyoutRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (enabled) TextPrimary else TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    color = TextSecondary,
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
internal fun ChromeFlyoutEmpty(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = AndySpace.Space6),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = TextSecondary, fontFamily = DisplayFont, fontSize = 12.sp)
    }
}
