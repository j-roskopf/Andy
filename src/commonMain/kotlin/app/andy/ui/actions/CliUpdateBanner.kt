package app.andy.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentKind
import app.andy.service.CliUpdateInfo
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/** A stack of small floating snackbar pills, one per CLI with a newer published version. */
@Composable
internal fun CliUpdateSnackbarStack(
    items: List<CliUpdateInfo>,
    updating: Set<AgentKind>,
    onUpdate: (CliUpdateInfo) -> Unit,
    onDismiss: (CliUpdateInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.wrapContentWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEach { item ->
            val isUpdating = item.kind in updating
            Row(
                Modifier
                    .clip(RoundedCornerShape(AndyRadius.Menu))
                    .background(AndyColors.Neutral900)
                    .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(AndyRadius.Menu))
                    .let {
                        if (isUpdating) it else it.pointerHoverIcon(PointerIcon.Hand).clickable { onUpdate(item) }
                    }
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${item.kind.label} update",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (isUpdating) "Updating…" else "${item.installedVersion} → ${item.latestVersion}",
                    color = TextSecondary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
                if (!isUpdating) {
                    Text(
                        "✕",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onDismiss(item) }
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}
