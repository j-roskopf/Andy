package app.andy.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.BugCaptureDraft
import app.andy.ui.components.Button
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.Panel
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal actual fun BugCaptureDialog(onDismiss: () -> Unit, onSave: (BugCaptureDraft) -> Unit) {
    var title by remember { mutableStateOf("Bug capture") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Capture bug", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth(), placeholder = "Crash opening playlist")
                LabeledField(
                    "Notes / repro steps",
                    notes,
                    { notes = it },
                    Modifier.fillMaxWidth(),
                    singleLine = false,
                    minHeight = 120.dp,
                    placeholder = "What happened? What should have happened?",
                )
                Text(
                    "Saves the last 30 seconds of Andy actions, live video, and logcat.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(BugCaptureDraft(title, notes)) },
                enabled = title.trim().isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Save bug") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal actual fun ClipTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Clip text", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            LabeledField(
                "Text",
                text,
                { text = it },
                Modifier.fillMaxWidth(),
                singleLine = false,
                minHeight = 100.dp,
                placeholder = "Paste or type text to send",
            )
        },
        confirmButton = {
            Button(
                onClick = { onSend(text) },
                enabled = text.isNotBlank(),
                colors = primaryButtonColors(),
            ) { Text("Send") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
