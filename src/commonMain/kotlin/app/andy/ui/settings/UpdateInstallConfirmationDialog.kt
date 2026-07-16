package app.andy.ui.settings

import androidx.compose.runtime.Composable
import app.andy.service.AvailableUpdate

/**
 * A platform-hosted installer confirmation. On desktop this must be a native window because
 * the embedded Swing editor and Metal live view sit above Compose's in-window overlays.
 */
@Composable
internal expect fun UpdateInstallConfirmationDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
)
