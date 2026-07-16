package app.andy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.AvailableUpdate
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextSecondary

@Composable
internal actual fun UpdateInstallConfirmationDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Install Andy ${update.versionName}?",
                color = AndyColors.Neutral100,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The update has been downloaded and verified.",
                    color = AndyColors.Neutral200,
                    fontSize = 14.sp,
                )
                Text(
                    "Andy will close and install the update.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Rust),
            ) { Text("Restart and install", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
            ) { Text("Later") }
        },
        containerColor = PanelSoft,
        titleContentColor = AndyColors.Neutral100,
        textContentColor = AndyColors.Neutral300,
    )
}
