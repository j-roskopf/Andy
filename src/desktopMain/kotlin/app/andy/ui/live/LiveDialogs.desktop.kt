package app.andy.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
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
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 520.dp, height = 420.dp),
        title = "Capture bug",
        resizable = false,
        alwaysOnTop = true,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Panel, shape = RoundedCornerShape(0.dp)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Capture bug", color = TextPrimary, fontWeight = FontWeight.Bold)
                LabeledField("Title", title, { title = it }, Modifier.fillMaxWidth(), placeholder = "Crash opening playlist")
                LabeledField(
                    "Notes / repro steps",
                    notes,
                    { notes = it },
                    Modifier.fillMaxWidth().weight(1f),
                    singleLine = false,
                    minHeight = 140.dp,
                    placeholder = "What happened? What should have happened?",
                )
                Text(
                    "Saves the last 30 seconds of Andy actions, live video, and logcat.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onSave(BugCaptureDraft(title, notes)) },
                        enabled = title.trim().isNotBlank(),
                        colors = primaryButtonColors(),
                    ) { Text("Save bug") }
                }
            }
        }
    }
}

@Composable
internal actual fun ClipTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(width = 460.dp, height = 280.dp),
        title = "Clip text",
        resizable = false,
        alwaysOnTop = true,
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Panel) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Clip text", color = TextPrimary, fontWeight = FontWeight.Bold)
                LabeledField(
                    "Text",
                    text,
                    { text = it },
                    Modifier.fillMaxWidth().weight(1f),
                    singleLine = false,
                    minHeight = 100.dp,
                    placeholder = "Paste or type text to send",
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onSend(text) },
                        enabled = text.isNotBlank(),
                        colors = primaryButtonColors(),
                    ) { Text("Send") }
                }
            }
        }
    }
}
