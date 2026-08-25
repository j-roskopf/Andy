package app.andy.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidDevice
import app.andy.model.DeviceKind
import app.andy.pickFiles
import app.andy.service.AppService
import app.andy.service.DeviceService
import app.andy.service.HostFileService
import app.andy.ui.components.Button
import app.andy.ui.components.FormLayout
import app.andy.ui.components.FormLayoutRow
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TextField
import app.andy.ui.components.fieldColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal fun isEmulatorDevice(device: AndroidDevice?): Boolean =
    device?.kind == DeviceKind.Emulator

@Composable
internal fun LocationControlSection(
    devices: DeviceService,
    hostFiles: HostFileService?,
    serial: String?,
    isEmulator: Boolean,
    onStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lat by remember { mutableStateOf("37.7749") }
    var lon by remember { mutableStateOf("-122.4194") }
    var alt by remember { mutableStateOf("") }
    var intervalMs by remember { mutableStateOf("1000") }
    var route by remember { mutableStateOf<List<GeoFix>>(emptyList()) }
    var routeIndex by remember { mutableStateOf<Int?>(null) }
    var playJob by remember { mutableStateOf<Job?>(null) }
    val enabled = serial != null && isEmulator

    fun applyFix() {
        if (!enabled) {
            onStatus(if (serial == null) "Select an online device" else "Location requires an emulator")
            return
        }
        val la = lat.toDoubleOrNull()
        val lo = lon.toDoubleOrNull()
        if (la == null || lo == null) {
            onStatus("Invalid lat/lon")
            return
        }
        scope.launch {
            val result = devices.sendGeoFix(serial, GeoFix(la, lo, alt.toDoubleOrNull()))
            onStatus(result.stdout.ifBlank { result.stderr })
        }
    }

    StateSection(
        title = "Location",
        description = if (isEmulator) {
            "Inject GPS fixes and play GPX/KML routes through the emulator console."
        } else {
            "GPS injection requires an emulator (adb emu geo fix)."
        },
    ) {
        if (!isEmulator) Caption("Select an emulator to inject location.")
        FormLayout {
            FormLayoutRow {
                LabeledField("Latitude", lat, { lat = it }, Modifier.weight(1f))
                LabeledField("Longitude", lon, { lon = it }, Modifier.weight(1f))
            }
            FormLayoutRow {
                LabeledField("Altitude (optional)", alt, { alt = it }, Modifier.weight(1f))
                Row(
                    Modifier.weight(1f).padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = ::applyFix,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text("Apply", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { alt = "" },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text("Clear alt", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GEO_PRESETS.forEach { preset ->
                OutlinedButton(
                    onClick = {
                        lat = formatGeoCoordinate(preset.fix.latitude)
                        lon = formatGeoCoordinate(preset.fix.longitude)
                        alt = preset.fix.altitudeMeters?.let { formatGeoCoordinate(it) } ?: ""
                        if (enabled) {
                            scope.launch {
                                val result = devices.sendGeoFix(serial, preset.fix)
                                onStatus("${preset.label}: ${result.stdout.ifBlank { result.stderr }}")
                            }
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(preset.label, fontSize = 11.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        val path = pickFiles(allowMultiple = false).firstOrNull() ?: return@launch
                        val text = hostFiles?.read(path)?.content
                            ?: run {
                                onStatus("Host file service unavailable")
                                return@launch
                            }
                        val points = when {
                            path.endsWith(".kml", ignoreCase = true) || text.contains("<kml", ignoreCase = true) ->
                                parseKmlLineString(text)
                            else -> parseGpxTrack(text)
                        }
                        route = points
                        routeIndex = null
                        onStatus("Loaded ${points.size} track points from ${path.substringAfterLast('/')}")
                    }
                },
                enabled = enabled && hostFiles != null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text("Load GPX/KML…", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Box(Modifier.weight(1f)) {
                FormLayoutRow {
                LabeledField("Interval ms", intervalMs, { intervalMs = it }, Modifier.weight(1f))
                Button(
                    onClick = {
                        if (!enabled) {
                            onStatus("Location requires an emulator")
                            return@Button
                        }
                        if (route.isEmpty()) {
                            onStatus("Load a GPX/KML route first")
                            return@Button
                        }
                        playJob?.cancel()
                        playJob = scope.launch {
                            devices.playRoute(serial, route, intervalMs.toLongOrNull() ?: 1000L)
                                .catch { onStatus("Route playback failed: ${it.message}") }
                                .collect { idx ->
                                    routeIndex = idx
                                    onStatus("Route point ${idx + 1}/${route.size}")
                                }
                            onStatus("Route playback finished")
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.padding(top = 22.dp),
                ) {
                    Text("Play", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
            }
            OutlinedButton(
                onClick = {
                    playJob?.cancel()
                    playJob = null
                    onStatus("Route playback stopped")
                },
                enabled = playJob?.isActive == true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text("Stop", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
        if (route.isNotEmpty()) {
            Caption("Route: ${route.size} points" + (routeIndex?.let { " · at ${it + 1}" } ?: ""))
        }
    }
}

@Composable
internal fun SensorControlSection(
    devices: DeviceService,
    serial: String?,
    isEmulator: Boolean,
    onStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(EmulatorSensor.Accelerometer) }
    var x by remember { mutableStateOf("0") }
    var y by remember { mutableStateOf("0") }
    var z by remember { mutableStateOf("9.81") }
    var single by remember { mutableStateOf("0") }
    var available by remember { mutableStateOf<Map<String, List<Float>>>(emptyMap()) }
    val enabled = serial != null && isEmulator

    LaunchedEffect(serial, isEmulator) {
        if (serial != null && isEmulator) {
            available = devices.readSensors(serial)
            available[selected.emuName]?.let { seed ->
                if (selected.axes == 1) {
                    single = seed.firstOrNull()?.let { formatSensorValue(it) } ?: single
                } else {
                    x = seed.getOrNull(0)?.let { formatSensorValue(it) } ?: x
                    y = seed.getOrNull(1)?.let { formatSensorValue(it) } ?: y
                    z = seed.getOrNull(2)?.let { formatSensorValue(it) } ?: z
                }
            }
        }
    }

    StateSection(
        title = "Sensors",
        description = if (isEmulator) {
            "Override emulator sensors. Values are seeded from emu sensor status."
        } else {
            "Sensor injection requires an emulator (adb emu sensor)."
        },
    ) {
        if (!isEmulator) Caption("Select an emulator to override sensors.")
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EmulatorSensor.entries.forEach { sensor ->
                val present = available.isEmpty() || available.containsKey(sensor.emuName)
                OutlinedButton(
                    onClick = {
                        selected = sensor
                        available[sensor.emuName]?.let { seed ->
                            if (sensor.axes == 1) {
                                single = seed.firstOrNull()?.let { formatSensorValue(it) } ?: "0"
                            } else {
                                x = seed.getOrNull(0)?.let { formatSensorValue(it) } ?: "0"
                                y = seed.getOrNull(1)?.let { formatSensorValue(it) } ?: "0"
                                z = seed.getOrNull(2)?.let { formatSensorValue(it) } ?: "0"
                            }
                        }
                    },
                    enabled = enabled && present,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(sensor.name, fontSize = 10.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false)
                }
            }
        }
        if (selected.axes == 1) {
            StateValueTile(selected.name, single, { single = it }, "Set") {
                if (!enabled) {
                    onStatus("Sensors require an emulator")
                    return@StateValueTile
                }
                val v = single.toFloatOrNull()
                if (v == null) {
                    onStatus("Invalid value")
                    return@StateValueTile
                }
                scope.launch {
                    val result = devices.setSensor(serial, selected, listOf(v))
                    onStatus(result.stdout.ifBlank { result.stderr })
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AxisField("X", x) { x = it }
                AxisField("Y", y) { y = it }
                AxisField("Z", z) { z = it }
                Button(
                    onClick = {
                        if (!enabled) {
                            onStatus("Sensors require an emulator")
                            return@Button
                        }
                        val xv = x.toFloatOrNull()
                        val yv = y.toFloatOrNull()
                        val zv = z.toFloatOrNull()
                        if (xv == null || yv == null || zv == null) {
                            onStatus("Invalid axis values")
                            return@Button
                        }
                        scope.launch {
                            val result = devices.setSensor(serial, selected, listOf(xv, yv, zv))
                            onStatus(result.stdout.ifBlank { result.stderr })
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Set ${selected.name}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
                if (selected == EmulatorSensor.Accelerometer) {
                    OutlinedButton(
                        onClick = {
                            x = formatSensorValue(ACCEL_PRESET_FLAT[0])
                            y = formatSensorValue(ACCEL_PRESET_FLAT[1])
                            z = formatSensorValue(ACCEL_PRESET_FLAT[2])
                            if (enabled) {
                                scope.launch {
                                    val result = devices.setSensor(serial, EmulatorSensor.Accelerometer, ACCEL_PRESET_FLAT)
                                    onStatus("Flat: ${result.stdout.ifBlank { result.stderr }}")
                                }
                            }
                        },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) { Text("Flat", fontSize = 11.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false) }
                    OutlinedButton(
                        onClick = {
                            x = formatSensorValue(ACCEL_PRESET_PORTRAIT[0])
                            y = formatSensorValue(ACCEL_PRESET_PORTRAIT[1])
                            z = formatSensorValue(ACCEL_PRESET_PORTRAIT[2])
                            if (enabled) {
                                scope.launch {
                                    val result = devices.setSensor(serial, EmulatorSensor.Accelerometer, ACCEL_PRESET_PORTRAIT)
                                    onStatus("Portrait: ${result.stdout.ifBlank { result.stderr }}")
                                }
                            }
                        },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) { Text("Portrait upright", fontSize = 11.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
internal fun BatteryControlSection(
    devices: DeviceService,
    serial: String?,
    device: AndroidDevice?,
    onStatus: (String) -> Unit,
) {
    val apiLevel = device?.apiLevel?.toIntOrNull() ?: 0
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf("50") }
    val enabled = serial != null
    val thermalOk = apiLevel == 0 || apiLevel >= 29

    StateSection(
        title = "Battery & thermal",
        description = "Override battery state via dumpsys (works on physical devices). Thermal requires API 29+.",
    ) {
        StateValueTile("Battery level %", level, { level = it }, "Apply") {
            if (!enabled) {
                onStatus("Select an online device")
                return@StateValueTile
            }
            val pct = level.toIntOrNull()
            if (pct == null) {
                onStatus("Invalid level")
                return@StateValueTile
            }
            scope.launch {
                val result = devices.setBatteryLevel(serial, pct)
                onStatus(result.stdout.ifBlank { result.stderr })
            }
        }
        StateCommandTile(
            label = "Charging",
            primaryLabel = "Charge",
            onPrimary = {
                if (!enabled) {
                    onStatus("Select an online device")
                    return@StateCommandTile
                }
                scope.launch {
                    val result = devices.setBatteryCharging(serial, true)
                    onStatus(result.stdout.ifBlank { result.stderr })
                }
            },
            secondaryLabel = "Unplug",
            onSecondary = {
                if (!enabled) {
                    onStatus("Select an online device")
                    return@StateCommandTile
                }
                scope.launch {
                    val result = devices.setBatteryCharging(serial, false)
                    onStatus(result.stdout.ifBlank { result.stderr })
                }
            },
        )
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BatteryHealth.entries.forEach { h ->
                OutlinedButton(
                    onClick = {
                        if (enabled) {
                            scope.launch {
                                val result = devices.setBatteryHealth(serial, h)
                                onStatus(result.stdout.ifBlank { result.stderr })
                            }
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(h.label, fontSize = 10.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false)
                }
            }
        }
        Button(
            onClick = {
                if (!enabled) {
                    onStatus("Select an online device")
                    return@Button
                }
                scope.launch {
                    val result = devices.resetBattery(serial)
                    onStatus(result.stdout.ifBlank { result.stderr })
                }
            },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        ) {
            Text("Reset battery overrides", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Caption("Reset clears fake battery state. Always reset before leaving a long session.")
        if (!thermalOk) {
            Caption("Thermal override needs API 29+.")
        } else {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ThermalStatus.entries.forEach { t ->
                    OutlinedButton(
                        onClick = {
                            if (enabled) {
                                scope.launch {
                                    val result = devices.setThermalStatus(serial, t.code)
                                    onStatus(result.stdout.ifBlank { result.stderr })
                                }
                            }
                        },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(t.label, fontSize = 10.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TelephonyControlSection(
    devices: DeviceService,
    serial: String?,
    isEmulator: Boolean,
    onStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("5550100") }
    var message by remember { mutableStateOf("Hello from Andy") }
    var signal by remember { mutableStateOf("4") }
    val enabled = serial != null && isEmulator

    StateSection(
        title = "Telephony",
        description = if (isEmulator) {
            "Simulate calls, SMS, network type, and signal via the emulator console."
        } else {
            "Telephony simulation requires an emulator (adb emu gsm/sms)."
        },
    ) {
        if (!isEmulator) Caption("Select an emulator for telephony simulation.")
        StateValueTile("Phone number", number, { number = it }, "Call") {
            if (!enabled) {
                onStatus("Telephony requires an emulator")
                return@StateValueTile
            }
            scope.launch {
                val result = devices.simulateIncomingCall(serial, number)
                onStatus(result.stdout.ifBlank { result.stderr })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!enabled) return@Button
                    scope.launch {
                        val result = devices.acceptCall(serial, number)
                        onStatus(result.stdout.ifBlank { result.stderr })
                    }
                },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Accept", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
            OutlinedButton(
                onClick = {
                    if (!enabled) return@OutlinedButton
                    scope.launch {
                        val result = devices.cancelCall(serial, number)
                        onStatus(result.stdout.ifBlank { result.stderr })
                    }
                },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Cancel", fontSize = 11.sp, maxLines = 1, softWrap = false) }
        }
        StateValueTile("SMS message", message, { message = it }, "Send") {
            if (!enabled) {
                onStatus("Telephony requires an emulator")
                return@StateValueTile
            }
            scope.launch {
                val result = devices.sendSms(serial, number, message)
                onStatus(result.stdout.ifBlank { result.stderr })
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GsmDataType.entries.forEach { type ->
                OutlinedButton(
                    onClick = {
                        if (!enabled) return@OutlinedButton
                        scope.launch {
                            val result = devices.setNetworkType(serial, type)
                            onStatus(result.stdout.ifBlank { result.stderr })
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(type.label, fontSize = 10.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false)
                }
            }
        }
        StateValueTile("Signal bars 0–4", signal, { signal = it }, "Set") {
            if (!enabled) {
                onStatus("Telephony requires an emulator")
                return@StateValueTile
            }
            val bars = signal.toIntOrNull()
            if (bars == null) {
                onStatus("Invalid signal")
                return@StateValueTile
            }
            scope.launch {
                val result = devices.setSignalStrength(serial, bars)
                onStatus(result.stdout.ifBlank { result.stderr })
            }
        }
    }
}

@Composable
internal fun LocaleControlSection(
    devices: DeviceService,
    apps: AppService?,
    serial: String?,
    onStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tag by remember { mutableStateOf("en-US") }
    var current by remember { mutableStateOf<String?>(null) }
    var allowRestart by remember { mutableStateOf(false) }
    val enabled = serial != null

    LaunchedEffect(serial) {
        current = if (serial != null) devices.currentDeviceLocale(serial) else null
        current?.let { tag = it }
    }

    StateSection(
        title = "Locale",
        description = "Runtime locale via cmd locale (API 33+), setprop+restart, or per-app locales.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Caption("Current: ${current ?: "unknown"}")
            StateValueTile("Locale tag", tag, { tag = it }, "Apply") {
                if (!enabled) {
                    onStatus("Select an online device")
                    return@StateValueTile
                }
                scope.launch {
                    val change = devices.setDeviceLocale(
                        serial,
                        tag,
                        apps = apps,
                        allowFrameworkRestart = allowRestart,
                    )
                    onStatus("${change.result.stdout.ifBlank { change.result.stderr }} (${change.method.label})")
                    current = devices.currentDeviceLocale(serial)
                }
            }
        }
        StateCommandTile(
            label = "Allow framework restart",
            primaryLabel = if (allowRestart) "On" else "Enable",
            onPrimary = { allowRestart = true },
            secondaryLabel = "Off",
            onSecondary = { allowRestart = false },
        )
        Caption("Restart path drops active mirror/logcat. Prefer cmd locale or per-app.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PSEUDO_LOCALES.forEach { (pseudo, label) ->
                OutlinedButton(
                    onClick = {
                        tag = pseudo
                        if (enabled) {
                            scope.launch {
                                val change = devices.setDeviceLocale(
                                    serial,
                                    pseudo,
                                    apps = apps,
                                    allowFrameworkRestart = allowRestart,
                                )
                                onStatus("$label: ${change.result.stdout.ifBlank { change.result.stderr }}")
                                current = devices.currentDeviceLocale(serial)
                            }
                        }
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(label, fontSize = 11.sp, fontFamily = MonoFont, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 15.sp)
}

@Composable
private fun AxisField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.widthIn(min = 70.dp, max = 90.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AndyLayout.FieldHeight),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = fieldColors(),
        )
    }
}

@Composable
private fun StateSection(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(description, color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp, lineHeight = 16.sp)
        }
        FormLayout {
            content()
        }
    }
}

@Composable
private fun StateCommandTile(
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
                Text(primaryLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(secondaryLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun StateValueTile(
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
            Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

