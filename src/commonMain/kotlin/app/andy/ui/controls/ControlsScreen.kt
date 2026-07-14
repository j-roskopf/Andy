package app.andy.ui.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.DeviceService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorInput
import app.andy.ui.components.Button
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Toolbar("Device controls", status)

        ControlSectionsLayout(
            radiosAndDisplay = {
                ControlSection(
                    title = "Radios & display",
                    description = "Apply device-wide connectivity and appearance changes.",
                ) {
                    CommandTile(
                        label = "Airplane mode",
                        primaryLabel = "Enable",
                        onPrimary = { run("Airplane on", listOf("cmd", "connectivity", "airplane-mode", "enable")) },
                        secondaryLabel = "Disable",
                        onSecondary = { run("Airplane off", listOf("cmd", "connectivity", "airplane-mode", "disable")) },
                    )
                    CommandTile(
                        label = "Wi-Fi",
                        primaryLabel = "Enable",
                        onPrimary = { run("WiFi on", listOf("svc", "wifi", "enable")) },
                        secondaryLabel = "Disable",
                        onSecondary = { run("WiFi off", listOf("svc", "wifi", "disable")) },
                    )
                    CommandTile(
                        label = "Mobile data",
                        primaryLabel = "Enable",
                        onPrimary = { run("Data on", listOf("svc", "data", "enable")) },
                        secondaryLabel = "Disable",
                        onSecondary = { run("Data off", listOf("svc", "data", "disable")) },
                    )
                    CommandTile(
                        label = "Bluetooth",
                        primaryLabel = "Enable",
                        onPrimary = { run("Bluetooth on", listOf("cmd", "bluetooth_manager", "enable")) },
                        secondaryLabel = "Disable",
                        onSecondary = { run("Bluetooth off", listOf("cmd", "bluetooth_manager", "disable")) },
                    )
                    CommandTile(
                        label = "Dark theme",
                        primaryLabel = "Enable",
                        onPrimary = { run("Dark mode on", listOf("cmd", "uimode", "night", "yes")) },
                        secondaryLabel = "Disable",
                        onSecondary = { run("Dark mode off", listOf("cmd", "uimode", "night", "no")) },
                    )
                    ValueCommandTile(
                        label = "Font scale",
                        value = fontScale,
                        onValueChange = { fontScale = it },
                        actionLabel = "Apply",
                        onApply = { run("Font scale", listOf("settings", "put", "system", "font_scale", fontScale)) },
                    )
                }
            },
            debugBehavior = {
                ControlSection(
                    title = "Debug behavior",
                    description = "Expose visual diagnostics and control how testable the app lifecycle is.",
                    accent = AndyColors.Blue,
                ) {
                    CommandTile(
                        label = "Show taps",
                        primaryLabel = "Show",
                        onPrimary = { run("Show taps on", listOf("settings", "put", "system", "show_touches", "1")) },
                        secondaryLabel = "Hide",
                        onSecondary = { run("Show taps off", listOf("settings", "put", "system", "show_touches", "0")) },
                    )
                    CommandTile(
                        label = "Pointer location",
                        primaryLabel = "Show",
                        onPrimary = { run("Pointer on", listOf("settings", "put", "system", "pointer_location", "1")) },
                        secondaryLabel = "Hide",
                        onSecondary = { run("Pointer off", listOf("settings", "put", "system", "pointer_location", "0")) },
                    )
                    CommandTile(
                        label = "Layout bounds",
                        primaryLabel = "Show",
                        onPrimary = { run("Bounds on", listOf("setprop", "debug.layout", "true")) },
                        secondaryLabel = "Hide",
                        onSecondary = { run("Bounds off", listOf("setprop", "debug.layout", "false")) },
                    )
                    CommandTile(
                        label = "Keep activities",
                        primaryLabel = "Finish",
                        onPrimary = { run("Do not keep on", listOf("settings", "put", "global", "always_finish_activities", "1")) },
                        secondaryLabel = "Keep",
                        onSecondary = { run("Do not keep off", listOf("settings", "put", "global", "always_finish_activities", "0")) },
                    )
                    ValueCommandTile(
                        label = "Animation scale",
                        value = animationScale,
                        onValueChange = { animationScale = it },
                        actionLabel = "Apply",
                        onApply = {
                            run(
                                "Animation scale",
                                listOf(
                                    "sh", "-c",
                                    "settings put global window_animation_scale $animationScale; settings put global transition_animation_scale $animationScale; settings put global animator_duration_scale $animationScale",
                                ),
                            )
                        },
                    )
                }
            },
        )

        PanelCard(accent = Rust) {
            ControlSectionHeader(
                title = "Hardware navigation",
                description = "Send a key event directly through the active mirror connection.",
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HardwareCommand("Power") { key("Power", MirrorInput.Power) }
                HardwareCommand("Vol +") { key("Vol up", MirrorInput.Key(24)) }
                HardwareCommand("Vol −") { key("Vol down", MirrorInput.Key(25)) }
                HardwareCommand("Recents") { key("Recents", MirrorInput.Recents) }
                HardwareCommand("Home") { key("Home", MirrorInput.Home) }
                HardwareCommand("Back") { key("Back", MirrorInput.Back) }
                HardwareCommand("Rotate") { run("Rotate", listOf("settings", "put", "system", "user_rotation", "1")) }
            }
        }
    }
}

@Composable
private fun ControlSection(
    title: String,
    description: String,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    PanelCard(accent = accent) {
        ControlSectionHeader(title, description)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ControlSectionsLayout(
    radiosAndDisplay: @Composable () -> Unit,
    debugBehavior: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 920.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { radiosAndDisplay() }
                Column(Modifier.weight(1f)) { debugBehavior() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                radiosAndDisplay()
                debugBehavior()
            }
        }
    }
}

@Composable
private fun ControlSectionHeader(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(description, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CommandTile(
    label: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    ControlTile(label) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onPrimary,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text(primaryLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text(secondaryLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ValueCommandTile(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    actionLabel: String,
    onApply: () -> Unit,
) {
    ControlTile(label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).height(42.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                colors = fieldColors(),
            )
            Button(onClick = onApply, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ControlTile(label: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(AndyRadius.R3)
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 216.dp)
            .heightIn(min = 72.dp)
            .background(AndyColors.Neutral900.copy(alpha = 0.44f), shape)
            .border(1.dp, Border.copy(alpha = 0.80f), shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun HardwareCommand(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, Border.copy(alpha = 0.90f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = AndyColors.Neutral900.copy(alpha = 0.42f),
            contentColor = TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
