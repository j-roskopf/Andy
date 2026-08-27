package app.andy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Astryx EmptyState — centered title, optional description, icon, and actions.
 * Pass a single [title] for the common “no rows yet” case.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    compact: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val blockPadding = if (compact) AndySpace.Space4 else AndySpace.Space8
    val inlinePadding = if (compact) AndySpace.Space4 else AndySpace.Space6
    val gap = if (compact) AndySpace.Space2 else AndySpace.Space4
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inlinePadding, vertical = blockPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        icon?.invoke()
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space1),
        ) {
            Text(
                title,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 14.sp else 16.sp,
                lineHeight = if (compact) 20.sp else 24.sp,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Text(
                    description,
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = if (compact) 12.sp else 14.sp,
                    lineHeight = if (compact) 16.sp else 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}
