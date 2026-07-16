package app.andy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.andy.service.AvailableUpdate
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.Panel
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal actual fun UpdateInstallConfirmationDialog(
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 440.dp, height = 250.dp),
        title = "Install Andy ${update.versionName}",
        resizable = false,
        alwaysOnTop = true,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Panel,
            shape = RoundedCornerShape(0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Install Andy ${update.versionName}?",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Text(
                    "The update has been downloaded and verified.",
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
                Text(
                    "Andy will close, replace the installed app, and relaunch automatically. Your operating system may ask for permission if Andy is installed in a protected location.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Later") }
                    Button(onClick = onConfirm, colors = primaryButtonColors()) {
                        Text("Restart and install", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
