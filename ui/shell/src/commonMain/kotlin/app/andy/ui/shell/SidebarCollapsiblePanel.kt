package app.andy.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.andy.ui.components.AndyHorizontalDivider
import app.andy.ui.theme.AndyMotion
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Bottom-sidebar section: single-line disclosure (`▾ Host · Local`) and optional top divider.
 */
@Composable
internal fun SidebarCollapsiblePanel(
    title: String,
    detail: String,
    detailColor: Color = TextSecondary,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showTopDivider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (showTopDivider) {
            AndyHorizontalDivider(
                modifier = Modifier.padding(vertical = AndySpace.Space2),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = AndySpace.Space1, vertical = AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
        ) {
            Text(
                if (expanded) "▾" else "▸",
                color = TextSecondary,
                fontFamily = DisplayFont,
                fontSize = 11.sp,
            )
            Text(
                title,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                "·",
                color = Border.copy(alpha = 0.7f),
                fontFamily = DisplayFont,
                fontSize = 11.sp,
            )
            Text(
                detail,
                color = detailColor,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(AndyMotion.StandardMs, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(AndyMotion.FastMs)),
            exit = shrinkVertically(
                animationSpec = tween(AndyMotion.SmallMinMs, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(AndyMotion.MicroMinMs)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = AndySpace.Space3)
                    .padding(horizontal = AndySpace.Space1)
                    .padding(bottom = AndySpace.Space2),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
                content = content,
            )
        }
    }
}
