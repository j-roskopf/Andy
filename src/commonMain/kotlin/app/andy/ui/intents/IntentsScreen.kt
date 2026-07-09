package app.andy.ui.intents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.andy.model.IntentDraft
import app.andy.model.IntentMode
import app.andy.service.AndyServices
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.FormRow
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.Green
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun IntentsScreen(services: AndyServices, serial: String?) {
    val intentService = services.intents
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(IntentMode.DeepLink) }
    var action by remember { mutableStateOf("android.intent.action.VIEW") }
    var component by remember { mutableStateOf("") }
    var dataUri by remember { mutableStateOf("app://url") }
    var result by remember { mutableStateOf("") }
    val draft = IntentDraft(mode = mode, action = action, component = component, dataUri = dataUri)
    val command = intentService.buildCommand(draft).joinToString(" ")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntentMode.entries.forEach { item -> FilterPill(item.name, item == mode, Rust) { mode = item } }
            }
            FormRow("Action") { TextField(action, { action = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors()) }
            FormRow("Component") { TextField(component, { component = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors()) }
            FormRow("Data URI") { TextField(dataUri, { dataUri = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$ $command", color = Green, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (serial != null) scope.launch {
                        services.bugs.recordAction("intent", "Send ${mode.name}", command)
                        result = intentService.send(serial, draft).let { if (it.isSuccess) it.stdout.ifBlank { "Sent" } else it.stderr }
                    }
                }) { Text("Send") }
            }
        }
        PanelCard {
            Text("Result", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(result.ifBlank { "No intent sent yet." }, color = TextSecondary, fontFamily = FontFamily.Monospace)
        }
    }
}
