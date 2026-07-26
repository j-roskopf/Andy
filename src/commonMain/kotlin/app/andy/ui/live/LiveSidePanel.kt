package app.andy.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.PanelCard
import app.andy.ui.components.WorkspaceSectionLabel
import app.andy.ui.controls.FoldableControlsPanel
import app.andy.ui.controls.FoldablePosture
import app.andy.ui.logcat.LogcatState
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.Yellow
import app.andy.ui.logcat.LogcatPanel

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

    PanelCard(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    displayName ?: device?.displayName ?: "No device",
                    color = TextPrimary,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (serial != null) {
                    Text(
                        serial,
                        color = TextSecondary.copy(alpha = 0.82f),
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
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

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPill(
                LiveSideTab.Info.label,
                selectedTab == LiveSideTab.Info,
                Rust,
            ) { selectedTab = LiveSideTab.Info }
            if (showLogcat) {
                FilterPill(
                    LiveSideTab.Logcat.label,
                    selectedTab == LiveSideTab.Logcat,
                    Cyan,
                ) { selectedTab = LiveSideTab.Logcat }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                LiveSideTab.Info -> LiveInfoTabContent(
                    serial = serial,
                    device = device,
                    showMirrorStreamControls = showMirrorStreamControls,
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
        modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (device != null) {
            WorkspaceSectionLabel("Device")
            LiveDeviceFacts(device)
        }

        if (showMirrorStreamControls) {
            WorkspaceSectionLabel("Stream")
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill("720", maxSize == "720", Cyan) { onApplyPreset("720", "4") }
                FilterPill("1080", maxSize == "1080", Green) { onApplyPreset("1080", "8") }
                FilterPill("1440", maxSize == "1440", Yellow) { onApplyPreset("1440", "12") }
                FilterPill("Native", maxSize == "0", Rust) { onApplyPreset("0", "16") }
            }
            if (acceleratedMirror) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPill("Auto", rendererMode == MirrorRendererMode.Auto, Cyan) {
                        onRendererModeChange(MirrorRendererMode.Auto)
                        onReconnectMirror()
                    }
                    FilterPill("GPU", rendererMode == MirrorRendererMode.Accelerated, Green) {
                        onRendererModeChange(MirrorRendererMode.Accelerated)
                        onReconnectMirror()
                    }
                    FilterPill("CPU", rendererMode == MirrorRendererMode.Legacy, Rust) {
                        onRendererModeChange(MirrorRendererMode.Legacy)
                        onReconnectMirror()
                    }
                }
            }
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
                    Button(onClick = onReconnectMirror) { Text("Restart mirror") }
                }
            }
            Text(
                "Max edge is the stream longest side. 0 keeps native resolution.",
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 13.sp,
            )
            if (acceleratedMirror) {
                Text(
                    if (isWeb) {
                        "Auto uses WebCodecs when available, otherwise CPU."
                    } else {
                        "Auto uses inline Metal when available and falls back to CPU."
                    },
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                )
            }
        }

        if (foldable) {
            WorkspaceSectionLabel("Foldable")
            FoldableControlsPanel(
                hingeAngle = foldableHingeAngle,
                enabled = serial != null,
                onPostureSelected = onFoldablePostureSelected,
            )
        }

        WorkspaceSectionLabel("Capture")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactHardwareButton("Save bug", serial, onSaveBug)
            if (transferBusy) {
                OutlinedButton(onClick = onCancelTransfer) { Text("Cancel transfer") }
            }
        }

        if (device?.kind == DeviceKind.Emulator) {
            WorkspaceSectionLabel("Emulator")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onStopEmulator,
                    enabled = serial != null && !stoppingEmulator,
                ) {
                    Text(if (stoppingEmulator) "Stopping" else "Stop emulator")
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
            }
        }

        if (liveActionStatus.isNotBlank() || bugSaveStatus.isNotBlank()) {
            WorkspaceSectionLabel("Status")
            if (liveActionStatus.isNotBlank()) {
                Text(
                    liveActionStatus,
                    color = liveActionStatusColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    label,
                    color = TextSecondary.copy(alpha = 0.78f),
                    fontFamily = MonoFont,
                    fontSize = 10.sp,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    value,
                    color = TextPrimary,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
