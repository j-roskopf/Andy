package app.andy.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal actual fun ConfirmationDialog(
    confirmation: PendingConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(confirmation.title, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = { Text(confirmation.message, color = TextSecondary) },
        confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text(confirmation.confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
