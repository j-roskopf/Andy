package app.andy.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.andy.ui.shell.SuppressHeavyweightSurfacesWhileOpen
import app.andy.ui.theme.Panel

internal data class PendingConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String = "Confirm",
    /** Invoked when the user cancels / closes the dialog without confirming. */
    val onCancel: (() -> Unit)? = null,
    /**
     * Optional third choice offered beside confirm and cancel, for a destructive prompt that
     * has a non-destructive way out — "Keep chat" when discarding a temporary chat.
     */
    val neutralLabel: String? = null,
    val onNeutral: (() -> Unit)? = null,
    val onConfirm: () -> Unit,
)

@Composable
internal expect fun ConfirmationDialog(confirmation: PendingConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit)

/**
 * [AlertDialog] that stays visible over embedded Swing/Metal surfaces.
 *
 * Compose's in-window dialog layer paints *below* desktop interop hosts, so a plain AlertDialog
 * opened while a chat terminal, project terminal, or mirror is mounted is completely hidden
 * behind it. Registering with the shell drops those hosts out of composition while the dialog
 * is open. Prefer this over [AlertDialog] anywhere a dialog can overlay that content.
 */
@Composable
internal fun AndyAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Panel,
) {
    SuppressHeavyweightSurfacesWhileOpen()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        containerColor = containerColor,
    )
}
