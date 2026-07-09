package app.andy.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import app.andy.service.DeviceService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorInput
import app.andy.ui.components.Button
import app.andy.ui.components.FormRow
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.TextPrimary
import kotlinx.coroutines.launch

@Composable
internal fun ControlsScreen(devices: DeviceService, mirror: MirrorEngine, serial: String?) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready") }
    var fontScale by remember { mutableStateOf("1.0") }
    var animationScale by remember { mutableStateOf("1.0") }

    fun run(label: String, command: List<String>) {
        if (serial == null) {
            status = "Select an online device"
            return
        }
        scope.launch {
            val result = devices.shell(serial, command)
            status = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }

    fun key(label: String, input: MirrorInput) {
        scope.launch {
            val result = mirror.sendInput(input)
            status = "$label: " + if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar("Controls", status)
        PanelCard {
            Text("Radios and display", color = TextPrimary, fontWeight = FontWeight.Bold)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Airplane on", listOf("cmd", "connectivity", "airplane-mode", "enable")) }) { Text("Airplane on") }
                OutlinedButton(onClick = { run("Airplane off", listOf("cmd", "connectivity", "airplane-mode", "disable")) }) { Text("Airplane off") }
                Button(onClick = { run("WiFi on", listOf("svc", "wifi", "enable")) }) { Text("WiFi on") }
                OutlinedButton(onClick = { run("WiFi off", listOf("svc", "wifi", "disable")) }) { Text("WiFi off") }
                Button(onClick = { run("Data on", listOf("svc", "data", "enable")) }) { Text("Data on") }
                OutlinedButton(onClick = { run("Data off", listOf("svc", "data", "disable")) }) { Text("Data off") }
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Bluetooth on", listOf("cmd", "bluetooth_manager", "enable")) }) { Text("Bluetooth on") }
                OutlinedButton(onClick = { run("Bluetooth off", listOf("cmd", "bluetooth_manager", "disable")) }) { Text("Bluetooth off") }
                Button(onClick = { run("Dark mode on", listOf("cmd", "uimode", "night", "yes")) }) { Text("Dark on") }
                OutlinedButton(onClick = { run("Dark mode off", listOf("cmd", "uimode", "night", "no")) }) { Text("Dark off") }
            }
            FormRow("Font scale") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(fontScale, { fontScale = it }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    Button(onClick = { run("Font scale", listOf("settings", "put", "system", "font_scale", fontScale)) }) { Text("Apply") }
                }
            }
        }
        PanelCard {
            Text("Debug values", color = TextPrimary, fontWeight = FontWeight.Bold)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run("Show taps on", listOf("settings", "put", "system", "show_touches", "1")) }) { Text("Taps on") }
                OutlinedButton(onClick = { run("Show taps off", listOf("settings", "put", "system", "show_touches", "0")) }) { Text("Taps off") }
                Button(onClick = { run("Pointer on", listOf("settings", "put", "system", "pointer_location", "1")) }) { Text("Pointer on") }
                OutlinedButton(onClick = { run("Pointer off", listOf("settings", "put", "system", "pointer_location", "0")) }) { Text("Pointer off") }
                Button(onClick = { run("Bounds on", listOf("setprop", "debug.layout", "true")) }) { Text("Bounds on") }
                OutlinedButton(onClick = { run("Bounds off", listOf("setprop", "debug.layout", "false")) }) { Text("Bounds off") }
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { run("Do not keep on", listOf("settings", "put", "global", "always_finish_activities", "1")) }) { Text("No keep on") }
                OutlinedButton(onClick = { run("Do not keep off", listOf("settings", "put", "global", "always_finish_activities", "0")) }) { Text("No keep off") }
                TextField(animationScale, { animationScale = it }, singleLine = true, modifier = Modifier.width(110.dp).height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                Button(onClick = {
                    run("Animation scale", listOf("sh", "-c", "settings put global window_animation_scale $animationScale; settings put global transition_animation_scale $animationScale; settings put global animator_duration_scale $animationScale"))
                }) { Text("Apply anim") }
            }
        }
        PanelCard {
            Text("Hardware buttons", color = TextPrimary, fontWeight = FontWeight.Bold)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { key("Power", MirrorInput.Power) }) { Text("Power") }
                Button(onClick = { key("Vol up", MirrorInput.Key(24)) }) { Text("Vol +") }
                Button(onClick = { key("Vol down", MirrorInput.Key(25)) }) { Text("Vol -") }
                Button(onClick = { key("Recents", MirrorInput.Recents) }) { Text("Recents") }
                Button(onClick = { key("Home", MirrorInput.Home) }) { Text("Home") }
                Button(onClick = { key("Back", MirrorInput.Back) }) { Text("Back") }
                Button(onClick = { run("Rotate", listOf("settings", "put", "system", "user_rotation", "1")) }) { Text("Rotate") }
            }
        }
    }
}
