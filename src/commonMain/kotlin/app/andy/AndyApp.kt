package app.andy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.andy.service.AndyServices
import app.andy.service.IosTargetRegistry
import app.andy.service.MirrorEngine
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.OpenAgentTaskRequest
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.LiveMirrorSettings
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.shell.AndyShell
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.AndyTint
import app.andy.ui.theme.AndyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class AndyDestination(val label: String) {
    Devices("Devices"),
    Catalog("Catalog"),
    Live("Live"),
    Apps("Apps"),
    Logcat("Logcat"),
    Intents("Intents"),
    Files("Files & data"),
    ComputerFiles("Computer Files"),
    Network("Network"),
    Actions("Projects"),
    Agents("Agents"),
    Snapshots("Snapshots"),
    Controls("Controls"),
    Performance("Performance"),
    Tracing("Tracing"),
    Design("Design"),
    Accessibility("Accessibility"),
    Bugs("Bugs"),
    Recordings("Recordings"),
    Settings("Settings"),
}

/** Destinations that remain reachable while an iOS target is selected in the toolbar. */
fun AndyDestination.availableWithIosTarget(): Boolean = when (this) {
    AndyDestination.Live,
    AndyDestination.Devices,
    AndyDestination.Settings,
    AndyDestination.Catalog,
    AndyDestination.ComputerFiles,
    AndyDestination.Agents,
    AndyDestination.Actions,
    -> true
    else -> false
}

@Composable
fun AndyApp(
    services: AndyServices,
    requestedDestination: AndyDestination? = null,
    onDestinationConsumed: () -> Unit = {},
    requestedOpenAgentTask: OpenAgentTaskRequest? = null,
    onOpenAgentTaskConsumed: () -> Unit = {},
    requestPopOutMirror: Boolean = false,
    onPopOutMirrorRequestConsumed: () -> Unit = {},
    onPopOutMirror: (String?, String?) -> Unit = { _, _ -> },
    onPopOutDevice: (String, String) -> Unit = { _, _ -> },
    contentTopPadding: androidx.compose.ui.unit.Dp = 18.dp,
    initialProjectTaskId: String? = null,
    initialProjectTab: String? = null,
) {
    AndyShell(
        services = services,
        requestedDestination = requestedDestination,
        onDestinationConsumed = onDestinationConsumed,
        requestedOpenAgentTask = requestedOpenAgentTask,
        onOpenAgentTaskConsumed = onOpenAgentTaskConsumed,
        requestPopOutMirror = requestPopOutMirror,
        onPopOutMirrorRequestConsumed = onPopOutMirrorRequestConsumed,
        onPopOutMirror = onPopOutMirror,
        onPopOutDevice = onPopOutDevice,
        contentTopPadding = contentTopPadding,
        initialProjectTaskId = initialProjectTaskId,
        initialProjectTab = initialProjectTab,
    )
}

@Composable
fun AndyMirrorPopOut(
    services: AndyServices,
    serial: String?,
    deviceName: String? = null,
    mirror: MirrorEngine = services.mirror,
    gpuPresentation: Boolean = mirror === services.mirror,
    controlsVisible: Boolean = false,
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    tintId: String = AndyTint.Default.id,
    surfaceModeId: String = AndySurfaceMode.Tinted.id,
) {
    AndyTheme(tintId, surfaceModeId) {
        val scope = rememberCoroutineScope()
        var mirrorStatus by remember { mutableStateOf("Disconnected") }
        var connectResult by remember { mutableStateOf("") }
        var mirrorSession by remember { mutableStateOf<app.andy.service.MirrorSession?>(null) }
        val sendInput = rememberMirrorInputSender(services, serial, mirror)
        val chromeInset = contentTopPadding + if (controlsVisible) 12.dp else 0.dp
        val needsMetalHost = gpuPresentation || (serial != null && IosTargetRegistry.isIosTarget(serial))
        LaunchedEffect(mirror) {
            mirror.status.collectLatest { mirrorStatus = it }
        }
        LaunchedEffect(mirror) {
            mirror.session.collectLatest { mirrorSession = it }
        }
        LaunchedEffect(serial, mirror, gpuPresentation, needsMetalHost) {
            if (serial == null) return@LaunchedEffect
            if (needsMetalHost) {
                awaitMirrorSurfaceReady()
            } else {
                // CPU pop-outs only need the SwingPanel laid out; avoid waiting on Metal hosts.
                delay(32)
            }
            val base = LiveMirrorSettings.config.value
            val config = if (gpuPresentation) {
                base
            } else {
                base.copy(rendererMode = MirrorRendererMode.Legacy)
            }
            val result = mirror.connect(serial, config)
            connectResult = if (result.isSuccess) result.stdout else result.stderr
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(top = chromeInset),
        ) {
            MirrorFrameContent(mirror, serial) { frameFlow, frame ->
                LiveDevicePane(
                    serial = serial,
                    device = null,
                    displayName = deviceName,
                    frame = frame,
                    frameFlow = frameFlow,
                    mirrorStatus = mirrorStatus,
                    mirrorSession = mirrorSession,
                    connectResult = connectResult,
                    modifier = Modifier.fillMaxSize(),
                    showDeviceHeader = controlsVisible,
                    showChromeControls = controlsVisible,
                    showContainerChrome = controlsVisible,
                    deviceBorderWidth = if (controlsVisible) 5.dp else 0.dp,
                    deviceCornerRadius = if (controlsVisible) 10.dp else 0.dp,
                    registerNativeHost = needsMetalHost,
                    onPower = { sendInput(MirrorInput.Power) },
                    onVolumeUp = { sendInput(MirrorInput.Key(24)) },
                    onVolumeDown = { sendInput(MirrorInput.Key(25)) },
                    onRotate = {
                        if (serial != null) scope.launch { services.devices.shell(serial, listOf("settings", "put", "system", "user_rotation", "1")) }
                    },
                    onCaptureScreenshot = {
                        if (serial != null) scope.launch { services.artifacts.saveScreenshot(serial, "andy-${serial}.png") }
                    },
                    onBugReport = {
                        if (serial != null) scope.launch { services.artifacts.saveBugReport(serial, "andy-bugreport-${serial}.zip") }
                    },
                    onClipText = {},
                    onPopOut = {},
                    showPopOut = false,
                    onInput = sendInput,
                    onConnect = {
                        if (serial != null) scope.launch {
                            val base = LiveMirrorSettings.config.value
                            val config = if (gpuPresentation) base else base.copy(rendererMode = MirrorRendererMode.Legacy)
                            val result = mirror.connect(serial, config)
                            connectResult = if (result.isSuccess) result.stdout else result.stderr
                        }
                    },
                )
            }
        }
    }
}
