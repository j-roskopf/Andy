package app.andy.ui.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.andy.ui.components.overlayOutlinedButtonColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.andy.model.AndroidDevice
import app.andy.model.VirtualDevice
import app.andy.service.AppService
import app.andy.service.AvdService
import app.andy.service.DeviceService
import app.andy.service.HostFileService
import app.andy.service.IosDeviceService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorInput
import app.andy.ui.components.Button
import app.andy.ui.components.FormLayout
import app.andy.ui.components.FormLayoutRow
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ControlsScreen(
    devices: DeviceService,
    mirror: MirrorEngine,
    serial: String?,
    device: AndroidDevice? = null,
    avd: AvdService? = null,
    apps: AppService? = null,
    hostFiles: HostFileService? = null,
    hingeAngle: Float = 180f,
    onHingeAngleChange: (Float) -> Unit = {},
    iosMode: Boolean = false,
    iosDevices: IosDeviceService? = null,
) {
    if (iosMode) {
        IosControlsScreen(serial = serial, iosDevices = iosDevices ?: return)
        return
    }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready") }
    var fontScale by remember { mutableStateOf("1.0") }
    var animationScale by remember { mutableStateOf("1.0") }
    var virtualDevices by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var previousSerial by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(device?.kind, device?.displayName, avd) {
        virtualDevices = if (avd != null && device?.kind == app.andy.model.DeviceKind.Emulator) {
            runCatching { avd.listVirtualDevices() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    // Clear sticky battery overrides when nothing is selected.
    LaunchedEffect(serial) {
        val prior = previousSerial
        if (serial == null && prior != null) {
            runCatching { devices.resetBattery(prior) }
        }
        previousSerial = serial
    }
    val foldable = isFoldableEmulator(device, virtualDevices)
    val isEmulator = isEmulatorDevice(device) || serial?.startsWith("emulator-") == true

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

    fun applyPosture(posture: FoldablePosture) {
        if (serial == null) {
            status = "Select an online device"
            return
        }
        onHingeAngleChange(posture.defaultAngle)
        scope.launch {
            val result = devices.setFoldablePosture(serial, posture)
            status = if (result.isSuccess) result.stdout.ifBlank { "ok" } else result.stderr.ifBlank { result.stdout }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Toolbar("Device controls", status)

        if (foldable) {
            FoldableControlsPanel(
                hingeAngle = hingeAngle,
                enabled = serial != null,
                onPostureSelected = ::applyPosture,
            )
        }

        val radiosSection: @Composable () -> Unit = {
            ControlSection(
                title = "Radios & display",
                description = "Apply device-wide connectivity and appearance changes.",
                accent = Rust,
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
        }
        val debugSection: @Composable () -> Unit = {
            ControlSection(
                title = "Debug behavior",
                description = "Expose visual diagnostics and control how testable the app lifecycle is.",
                accent = Rust,
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
                    label = "TalkBack",
                    primaryLabel = "Enable",
                    onPrimary = { run("TalkBack on", talkBackCommand(enabled = true)) },
                    secondaryLabel = "Disable",
                    onSecondary = { run("TalkBack off", talkBackCommand(enabled = false)) },
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
        }
        val locationSection: @Composable () -> Unit = {
            LocationControlSection(devices, hostFiles, serial, isEmulator) { status = it }
        }
        val sensorSection: @Composable () -> Unit = {
            SensorControlSection(devices, serial, isEmulator) { status = it }
        }
        val batterySection: @Composable () -> Unit = {
            BatteryControlSection(devices, serial, device) { status = it }
        }
        val telephonySection: @Composable () -> Unit = {
            TelephonyControlSection(devices, serial, isEmulator) { status = it }
        }
        val localeSection: @Composable () -> Unit = {
            LocaleControlSection(devices, apps, serial) { status = it }
        }
        ControlSectionsLayout(
            sections = buildList {
                add(radiosSection)
                add(debugSection)
                if (isEmulator) {
                    add(locationSection)
                    add(sensorSection)
                    add(telephonySection)
                }
                add(batterySection)
                add(localeSection)
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
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
                HardwareCommand("Rotate") {
                    if (serial == null) {
                        status = "Select an online device"
                        return@HardwareCommand
                    }
                    scope.launch {
                        val result = devices.rotateDeviceDisplay(serial, isEmulator)
                        status = "Rotate: " + if (result.isSuccess) {
                            result.stdout.ifBlank { "ok" }
                        } else {
                            result.stderr.ifBlank { result.stdout }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlSection(
    title: String,
    description: String,
    @Suppress("UNUSED_PARAMETER") accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
        ControlSectionHeader(title, description)
        FormLayout {
            content()
        }
    }
}

@Composable
internal fun ControlSectionsLayout(
    sections: List<@Composable () -> Unit>,
) {
    if (sections.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 920.dp && sections.size >= 2) {
            val midpoint = (sections.size + 1) / 2
            val left = sections.take(midpoint)
            val right = sections.drop(midpoint)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    left.forEach { section -> section() }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    right.forEach { section -> section() }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                sections.forEach { section -> section() }
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
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    primaryLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    secondaryLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
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
    FormLayoutRow {
        LabeledField(label, value, onValueChange, Modifier.weight(1f))
        Button(
            onClick = onApply,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.padding(top = 22.dp),
        ) {
            Text(
                actionLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun ControlTile(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 300.dp)
            .heightIn(min = 72.dp),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Text(label, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

private fun talkBackCommand(enabled: Boolean): List<String> {
    val script = if (enabled) {
        """
        talkback='com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService'
        pm path com.google.android.marvin.talkback >/dev/null 2>&1 || {
          echo 'TalkBack is not installed on this device' >&2
          exit 1
        }
        current="__DOLLAR__(settings get secure enabled_accessibility_services)"
        [ "__DOLLAR__current" = 'null' ] && current=''
        case ":__DOLLAR__current:" in
          *":__DOLLAR__talkback:"*) ;;
          *) settings put secure enabled_accessibility_services "__DOLLAR__{current:+__DOLLAR__current:}__DOLLAR__talkback" ;;
        esac
        settings put secure accessibility_enabled 1
        """.trimIndent()
    } else {
        """
        talkback='com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService'
        current="__DOLLAR__(settings get secure enabled_accessibility_services)"
        [ "__DOLLAR__current" = 'null' ] && current=''
        remaining="__DOLLAR__(printf '%s' "__DOLLAR__current" | tr ':' '\n' | grep -Fvx "__DOLLAR__talkback" | paste -sd: -)"
        settings put secure enabled_accessibility_services "__DOLLAR__remaining"
        [ -n "__DOLLAR__remaining" ] || settings put secure accessibility_enabled 0
        """.trimIndent()
    }
    return listOf("sh", "-c", script.replace("__DOLLAR__", "\$"))
}

@Composable
private fun HardwareCommand(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, PaneDividerTint),
        colors = overlayOutlinedButtonColors(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        Text(
            label,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}
