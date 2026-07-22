package app.andy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.andy.desktop.ApplyMacWindowChrome
import app.andy.desktop.macTitleBarContentInset
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Red
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * A native window is required on desktop because embedded Swing/Metal surfaces paint above
 * Compose's in-window AlertDialog layer. This keeps destructive confirmations actionable over
 * bug-replay video and other heavyweight content.
 */
@Composable
internal actual fun ConfirmationDialog(
    confirmation: PendingConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 440.dp, height = 230.dp),
        title = confirmation.title,
        resizable = false,
        alwaysOnTop = true,
    ) {
        ApplyMacWindowChrome(Panel)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Panel,
            shape = RoundedCornerShape(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 24.dp + macTitleBarContentInset,
                        bottom = 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    confirmation.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    confirmation.message,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                    ) { Text(confirmation.confirmLabel) }
                }
            }
        }
    }
}
