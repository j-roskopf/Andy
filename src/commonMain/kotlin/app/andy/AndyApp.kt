package app.andy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.awaitCancellation
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
import app.andy.service.TargetCapabilities
import app.andy.ui.live.LiveDevicePane
import app.andy.ui.live.LiveMirrorSettings
import app.andy.ui.live.MirrorFrameContent
import app.andy.ui.live.rememberMirrorInputSender
import app.andy.ui.controls.rotateDeviceDisplay
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
    Inspector("Inspector"),
    Bugs("Bugs"),
    Recordings("Recordings"),
    Settings("Settings"),
}

internal val AndyDestination.showsSideChat: Boolean
    get() = this == AndyDestination.Actions || this == AndyDestination.Agents

/**
 * Destinations reachable while an iOS target is selected. Delegates to [TargetCapabilities]
 * so each phase enables screens by declaring a capability rather than editing this `when`.
 */
fun AndyDestination.availableWithIosTarget(
    capabilities: TargetCapabilities = TargetCapabilities.Simulator,
): Boolean = capabilities.destinationAvailable(this)

/** Settings is always reachable and never appears in the "customize sidebar" list. */
fun AndyDestination.isToggleableInSidebar(): Boolean = this != AndyDestination.Settings

/**
 * Destinations shown while the desktop GUI is SSH-remoted to another machine.
 * Keep panes that work over the tunneled remote adb/scrcpy stack; hide local-only tooling.
 */
fun AndyDestination.availableWhileRemote(): Boolean = when (this) {
    AndyDestination.Devices,
    AndyDestination.Live,
    AndyDestination.Apps,
    AndyDestination.Logcat,
    AndyDestination.Intents,
    AndyDestination.Files,
    AndyDestination.Actions,
    AndyDestination.Agents,
    AndyDestination.Controls,
    AndyDestination.Settings,
    -> true
    // Still local-only / not exposed over the remote adb tunnel yet.
    AndyDestination.Catalog,
    AndyDestination.ComputerFiles,
    AndyDestination.Network,
    AndyDestination.Snapshots,
    AndyDestination.Performance,
    AndyDestination.Tracing,
    AndyDestination.Design,
    AndyDestination.Inspector,
    AndyDestination.Bugs,
    AndyDestination.Recordings,
    -> false
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
    poppedOutTargetIds: Set<String> = emptySet(),
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
        poppedOutTargetIds = poppedOutTargetIds,
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
    mirrorHostWindow: Any? = null,
    controlsVisible: Boolean = false,
    contentTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    tintId: String = AndyTint.Default.id,
    surfaceModeId: String = AndySurfaceMode.PitchBlack.id,
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
        LaunchedEffect(serial, mirror, gpuPresentation, needsMetalHost, mirrorHostWindow) {
            if (serial == null) return@LaunchedEffect
            if (needsMetalHost) {
                awaitMirrorSurfaceReadyInWindow(mirrorHostWindow)
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
            try {
                awaitCancellation()
            } finally {
                if (mirror !== services.mirror) {
                    mirror.disconnect(immediate = true)
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(top = chromeInset),
        ) {
            key(mirror, gpuPresentation) {
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
                    registerNativeHostFill = needsMetalHost,
                    mirrorStreamKey = serial,
                    onPower = { sendInput(MirrorInput.Power) },
                    onVolumeUp = { sendInput(MirrorInput.Key(24)) },
                    onVolumeDown = { sendInput(MirrorInput.Key(25)) },
                    onRotate = {
                        if (serial != null) {
                            scope.launch {
                                val rotation = services.devices.rotateDeviceDisplay(
                                    serial = serial,
                                    isEmulator = serial.startsWith("emulator-"),
                                )
                                if (!rotation.isSuccess) {
                                    connectResult = rotation.stderr.ifBlank { rotation.stdout }
                                    return@launch
                                }
                                val base = LiveMirrorSettings.config.value
                                val config = if (gpuPresentation) {
                                    base
                                } else {
                                    base.copy(rendererMode = MirrorRendererMode.Legacy)
                                }
                                val restart = mirror.restartForDisplayChange(serial, config)
                                connectResult = if (restart.isSuccess) {
                                    rotation.stdout.ifBlank { "Rotated device" }
                                } else {
                                    restart.stderr.ifBlank { restart.stdout }
                                }
                            }
                        }
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
}
