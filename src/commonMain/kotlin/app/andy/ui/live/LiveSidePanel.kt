package app.andy.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidDevice
import app.andy.model.DeviceKind
import app.andy.service.AppService
import app.andy.service.LogcatService
import app.andy.service.MirrorRendererMode
import app.andy.ui.actions.DockPlacement
import app.andy.ui.actions.TerminalDockToggleRow
import app.andy.ui.components.Button
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.SegmentedControl
import app.andy.ui.components.TabBar
import app.andy.ui.components.WorkspaceSectionLabel
import app.andy.ui.controls.FoldableControlsPanel
import app.andy.ui.controls.FoldablePosture
import app.andy.ui.logcat.LogcatPanel
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

internal enum class LiveSideTab(val label: String) {
    Info("Info"),
    Logcat("Logcat"),
}

@Composable
internal fun LiveSidePanel(
    serial: String?,
    device: AndroidDevice?,
    displayName: String?,
    showLogcat: Boolean,
    showMirrorStreamControls: Boolean,
    showAndroidAuto: Boolean = false,
    androidAutoEnabled: Boolean = false,
    onAndroidAutoEnabledChange: (Boolean) -> Unit = {},
    androidAutoReadyHint: String? = null,
    acceleratedMirror: Boolean,
    isWeb: Boolean,
    maxSize: String,
    bitRateMbps: String,
    maxFps: String,
    rendererMode: MirrorRendererMode,
    onMaxSizeChange: (String) -> Unit,
    onBitRateMbpsChange: (String) -> Unit,
    onMaxFpsChange: (String) -> Unit,
    onRendererModeChange: (MirrorRendererMode) -> Unit,
    onApplyPreset: (String, String) -> Unit,
    onReconnectMirror: () -> Unit,
    foldable: Boolean,
    foldableHingeAngle: Float,
    onFoldablePostureSelected: (FoldablePosture) -> Unit,
    transferBusy: Boolean,
    onCancelTransfer: () -> Unit,
    liveActionStatus: String,
    liveActionStatusColor: androidx.compose.ui.graphics.Color,
    onSaveBug: () -> Unit,
    onStopEmulator: () -> Unit,
    stoppingEmulator: Boolean,
    stopStatus: String,
    bugSaveStatus: String,
    terminalPlacement: DockPlacement?,
    onTerminalToggle: (DockPlacement) -> Unit,
    logcat: LogcatService,
    appsService: AppService,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    logcatState: LogcatState,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember(showLogcat) {
        mutableStateOf(LiveSideTab.Info)
    }
    if (!showLogcat && selectedTab == LiveSideTab.Logcat) {
        selectedTab = LiveSideTab.Info
    }
    val tabs = remember(showLogcat) {
        buildList {
            add(LiveSideTab.Info)
            if (showLogcat) add(LiveSideTab.Logcat)
        }
    }

    Column(
        modifier
            .fillMaxHeight()
            .padding(horizontal = AndySpace.Space4, vertical = AndySpace.Space3),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space4),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    displayName ?: device?.displayName ?: "No device",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (serial != null) {
                    Text(
                        serial,
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TerminalDockToggleRow(
                terminalPlacement = terminalPlacement,
                onToggle = onTerminalToggle,
            )
        }

        TabBar(
            tabs = tabs,
            selected = selectedTab,
            onSelect = { selectedTab = it },
            label = { it.label },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                LiveSideTab.Info -> LiveInfoTabContent(
                    serial = serial,
                    device = device,
                    showMirrorStreamControls = showMirrorStreamControls,
                    showAndroidAuto = showAndroidAuto,
                    androidAutoEnabled = androidAutoEnabled,
                    onAndroidAutoEnabledChange = onAndroidAutoEnabledChange,
                    androidAutoReadyHint = androidAutoReadyHint,
                    acceleratedMirror = acceleratedMirror,
                    isWeb = isWeb,
                    maxSize = maxSize,
                    bitRateMbps = bitRateMbps,
                    maxFps = maxFps,
                    rendererMode = rendererMode,
                    onMaxSizeChange = onMaxSizeChange,
                    onBitRateMbpsChange = onBitRateMbpsChange,
                    onMaxFpsChange = onMaxFpsChange,
                    onRendererModeChange = onRendererModeChange,
                    onApplyPreset = onApplyPreset,
                    onReconnectMirror = onReconnectMirror,
                    foldable = foldable,
                    foldableHingeAngle = foldableHingeAngle,
                    onFoldablePostureSelected = onFoldablePostureSelected,
                    transferBusy = transferBusy,
                    onCancelTransfer = onCancelTransfer,
                    liveActionStatus = liveActionStatus,
                    liveActionStatusColor = liveActionStatusColor,
                    onSaveBug = onSaveBug,
                    onStopEmulator = onStopEmulator,
                    stoppingEmulator = stoppingEmulator,
                    stopStatus = stopStatus,
                    bugSaveStatus = bugSaveStatus,
                    modifier = Modifier.fillMaxSize(),
                )
                LiveSideTab.Logcat -> LogcatPanel(
                    logcat = logcat,
                    appsService = appsService,
                    serial = serial,
                    selectedPackage = selectedPackage,
                    onSelectedPackageChange = onSelectedPackageChange,
                    modifier = Modifier.fillMaxSize(),
                    compact = true,
                    embedded = true,
                    state = logcatState,
                )
            }
        }
    }
}

@Composable
private fun LiveInfoTabContent(
    serial: String?,
    device: AndroidDevice?,
    showMirrorStreamControls: Boolean,
    showAndroidAuto: Boolean = false,
    androidAutoEnabled: Boolean = false,
    onAndroidAutoEnabledChange: (Boolean) -> Unit = {},
    androidAutoReadyHint: String? = null,
    acceleratedMirror: Boolean,
    isWeb: Boolean,
    maxSize: String,
    bitRateMbps: String,
    maxFps: String,
    rendererMode: MirrorRendererMode,
    onMaxSizeChange: (String) -> Unit,
    onBitRateMbpsChange: (String) -> Unit,
    onMaxFpsChange: (String) -> Unit,
    onRendererModeChange: (MirrorRendererMode) -> Unit,
    onApplyPreset: (String, String) -> Unit,
    onReconnectMirror: () -> Unit,
    foldable: Boolean,
    foldableHingeAngle: Float,
    onFoldablePostureSelected: (FoldablePosture) -> Unit,
    transferBusy: Boolean,
    onCancelTransfer: () -> Unit,
    liveActionStatus: String,
    liveActionStatusColor: androidx.compose.ui.graphics.Color,
    onSaveBug: () -> Unit,
    onStopEmulator: () -> Unit,
    stoppingEmulator: Boolean,
    stopStatus: String,
    bugSaveStatus: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space5),
    ) {
        if (device != null) {
            LiveDeviceFacts(device)
            if (showAndroidAuto) {
                AndroidAutoToggle(
                    enabled = androidAutoEnabled,
                    onEnabledChange = onAndroidAutoEnabledChange,
                    readyHint = androidAutoReadyHint,
                )
            }
        }

        if (showMirrorStreamControls) {
            WorkspaceSectionLabel("Stream quality")
            val presetOptions = listOf("720", "1080", "1440", "Native")
            val presetValues = listOf("720", "1080", "1440", "0")
            val selectedPreset = presetValues.indexOf(maxSize).coerceAtLeast(0)
            SegmentedControl(
                options = presetOptions,
                selectedIndex = selectedPreset,
                onSelect = { index -> onApplyPreset(presetValues[index], bitRateMbps) },
            )

            if (acceleratedMirror) {
                WorkspaceSectionLabel("Renderer")
                val rendererOptions = listOf("Auto", "GPU", "CPU")
                val rendererValues = listOf(
                    MirrorRendererMode.Auto,
                    MirrorRendererMode.Accelerated,
                    MirrorRendererMode.Legacy,
                )
                SegmentedControl(
                    options = rendererOptions,
                    selectedIndex = rendererValues.indexOf(rendererMode).coerceAtLeast(0),
                    onSelect = { index ->
                        onRendererModeChange(rendererValues[index])
                        onReconnectMirror()
                    },
                )
            }

            WorkspaceSectionLabel("Advanced")
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LabeledField("Max edge", maxSize, { onMaxSizeChange(it.filter(Char::isDigit)) }, Modifier.width(96.dp))
                LabeledField("Mbps", bitRateMbps, { onBitRateMbpsChange(it.filter { ch -> ch.isDigit() || ch == '.' }) }, Modifier.width(88.dp))
                LabeledField("FPS", maxFps, { onMaxFpsChange(it.filter(Char::isDigit)) }, Modifier.width(78.dp))
                Box(Modifier.align(Alignment.Bottom).padding(bottom = 2.dp)) {
                    OutlinedButton(onClick = onReconnectMirror) { Text("Restart") }
                }
            }
            Text(
                "Max edge is the stream longest side. 0 keeps native resolution.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        if (foldable) {
            WorkspaceSectionLabel("Foldable")
            FoldableControlsPanel(
                hingeAngle = foldableHingeAngle,
                enabled = serial != null,
                onPostureSelected = onFoldablePostureSelected,
            )
        }

        WorkspaceSectionLabel("Actions")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSaveBug, enabled = serial != null) { Text("Save bug") }
            if (transferBusy) {
                OutlinedButton(onClick = onCancelTransfer) { Text("Cancel transfer") }
            }
            if (device?.kind == DeviceKind.Emulator) {
                OutlinedButton(
                    onClick = onStopEmulator,
                    enabled = serial != null && !stoppingEmulator,
                ) {
                    Text(if (stoppingEmulator) "Stopping…" else "Stop emulator")
                }
            }
        }
        if (stopStatus.isNotBlank()) {
            Text(
                stopStatus,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (liveActionStatus.isNotBlank() || bugSaveStatus.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (liveActionStatus.isNotBlank()) {
                    Text(
                        liveActionStatus,
                        color = liveActionStatusColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (bugSaveStatus.isNotBlank()) {
                    Text(
                        bugSaveStatus,
                        color = Rust,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveDeviceFacts(device: AndroidDevice) {
    val facts = buildList {
        device.model?.let { add("Model" to it) }
        device.apiLevel?.let { add("API" to it) }
        device.abi?.let { add("ABI" to it) }
        device.screenSize?.let { add("Screen" to it) }
        device.batteryPercent?.let { add("Battery" to "$it%") }
        device.transport.name.takeIf { it != "Unknown" }?.let { add("Transport" to it) }
        add("Kind" to device.kind.name)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        facts.forEach { (label, value) ->
            Text(
                "$label  $value",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
