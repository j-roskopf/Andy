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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    contentAlignment: Alignment.Horizontal = Alignment.Start,
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
        Column(
            Modifier
                .fillMaxWidth()
                .background(AndyColors.SurfaceRaised)
                .bottomBorder(PaneDividerTint)
                .padding(horizontal = AndySpace.Space5, vertical = AndySpace.Space3)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = contentAlignment,
            content = content,
        )
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
