package app.andy.ui.components

import androidx.compose.runtime.Composable

internal data class PendingConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String = "Confirm",
    val onConfirm: () -> Unit,
)

@Composable
internal expect fun ConfirmationDialog(confirmation: PendingConfirmation, onDismiss: () -> Unit, onConfirm: () -> Unit)
