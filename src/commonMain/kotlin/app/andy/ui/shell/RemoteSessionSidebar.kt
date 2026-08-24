package app.andy.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import app.andy.ui.components.TextButton
import app.andy.ui.components.CodeFieldTextStyle
import app.andy.ui.components.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.RemoteSessionService
import app.andy.service.RemoteSessionState
import app.andy.service.RemoteSessionStatus
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Bottom-left host switcher: Local + saved SSH remotes. Click a row to switch;
 * add new hosts with the field below.
 */
@Composable
internal fun RemoteSessionSidebarControls(
    remoteSession: RemoteSessionService,
    session: RemoteSessionState,
    expanded: Boolean,
    panelOpen: Boolean,
    onPanelOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var draftTarget by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun runSwitch(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }
            busy = false
        }
    }

    val badge = when (session.status) {
        RemoteSessionStatus.Connected -> session.target.orEmpty()
        RemoteSessionStatus.Connecting -> "Connecting…"
        RemoteSessionStatus.Error -> session.error?.take(40) ?: "Remote error"
        RemoteSessionStatus.Local -> "Local"
    }
    val badgeColor = when (session.status) {
        RemoteSessionStatus.Connected -> Rust
        RemoteSessionStatus.Connecting -> TextSecondary
        RemoteSessionStatus.Error -> AndyColors.TextTertiary
        RemoteSessionStatus.Local -> AndyColors.TextTertiary
    }

    if (!expanded) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(vertical = AndySpace.Space1)
                .background(AndyColors.SidebarBg, RoundedCornerShape(AndyRadius.Control))
                .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = when (session.status) {
                    RemoteSessionStatus.Connected -> "R"
                    RemoteSessionStatus.Connecting -> "…"
                    else -> "L"
                },
                color = badgeColor,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
        }
        return
    }

    SidebarCollapsiblePanel(
        title = "Host",
        detail = badge,
        detailColor = badgeColor,
        expanded = panelOpen,
        onExpandedChange = onPanelOpenChange,
        showTopDivider = true,
        modifier = modifier,
    ) {
        if (session.error != null && session.status != RemoteSessionStatus.Connected) {
            Text(
                session.error,
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                maxLines = 4,
            )
        }
        if (busy || session.status == RemoteSessionStatus.Connecting) {
            Text(
                "Switching…",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }

        HostRow(
            label = "Local",
            selected = session.status == RemoteSessionStatus.Local ||
                (session.status == RemoteSessionStatus.Error && !session.isRemote),
            enabled = !busy && session.status != RemoteSessionStatus.Connecting,
            onClick = {
                if (session.isRemote || session.status == RemoteSessionStatus.Connecting) {
                    runSwitch { remoteSession.disconnect() }
                }
            },
        )

        session.savedTargets.forEach { saved ->
            val selected = session.isRemote && session.target == saved
            HostRow(
                label = saved,
                selected = selected,
                enabled = !busy && session.status != RemoteSessionStatus.Connecting,
                onRemove = {
                    scope.launch { remoteSession.removeSavedTarget(saved) }
                },
                onClick = {
                    if (!selected) {
                        runSwitch { remoteSession.connect(saved) }
                    }
                },
            )
        }

        TextField(
            value = draftTarget,
            onValueChange = { draftTarget = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            textStyle = CodeFieldTextStyle(),
            placeholder = {
                Text(
                    "user@host",
                    color = AndyColors.TextTertiary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                )
            },
        )
        TextButton(
            onClick = {
                val target = draftTarget.trim()
                if (target.isEmpty()) return@TextButton
                runSwitch {
                    remoteSession.addSavedTarget(target)
                    remoteSession.connect(target)
                    if (remoteSession.state.value.isRemote) {
                        draftTarget = ""
                    }
                }
            },
            enabled = !busy && draftTarget.isNotBlank(),
        ) { Text("Save & switch", fontSize = 11.sp) }

        if (session.isRemote) {
            TextButton(
                onClick = { runSwitch { remoteSession.reconnect() } },
                enabled = !busy,
            ) { Text("Reconnect current", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun HostRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) Rust.copy(alpha = 0.18f) else AndyColors.SidebarBg,
                RoundedCornerShape(AndyRadius.Control),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) Rust else AndyColors.TextTertiary,
            fontSize = 10.sp,
        )
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontFamily = MonoFont,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null) {
            Text(
                "✕",
                color = AndyColors.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.clickable(enabled = enabled, onClick = onRemove),
            )
        }
    }
}
