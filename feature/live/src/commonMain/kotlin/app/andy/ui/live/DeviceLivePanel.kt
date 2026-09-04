package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.andy.model.AndroidDevice
import app.andy.service.AndyServices
import app.andy.service.MirrorEngine
import app.andy.service.MirrorSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceLivePanel(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    displayName: String? = device?.displayName,
    modifier: Modifier = Modifier,
    mirror: MirrorEngine = services.mirror,
    showChromeControls: Boolean = true,
    showDeviceHeader: Boolean = true,
    showPopOut: Boolean = true,
    showContainerChrome: Boolean = true,
    deviceBorderWidth: Dp = 5.dp,
    deviceCornerRadius: Dp = 10.dp,
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var mirrorSession by remember { mutableStateOf<MirrorSession?>(null) }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial, mirror)
    LaunchedEffect(mirror) {
        mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(mirror, serial) {
        mirror.session.collectLatest { session ->
            mirrorSession = session?.takeIf { it.serial == serial }
        }
    }
    fun connect() {
        if (serial != null) {
            scope.launch {
                val result = mirror.connect(serial, LiveMirrorSettings.config.value)
                connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
            }
        }
    }
    // Connect on enter; leave the session warm when the panel leaves composition so Live /
    // Design / Performance handoffs stay instant (same pattern as DesignScreen).
    // Pooled dock/pop-out engines are refcounted by the shell — do not disconnect them here
    // when [serial] clears; that would tear down a still-held session.
    LaunchedEffect(mirror, serial) {
        connectResult = ""
        if (serial != null) {
            val result = mirror.connect(serial, LiveMirrorSettings.config.value)
            connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
        } else if (mirror === services.mirror) {
            withContext(NonCancellable) {
                mirror.disconnect()
            }
        }
    }
    MirrorFrameContent(mirror, serial) { frameFlow, frame ->
        LiveDevicePane(
            serial = serial,
            device = device,
            displayName = displayName,
            frame = frame,
            frameFlow = frameFlow,
            mirrorStatus = mirrorStatus,
            mirrorSession = mirrorSession,
            connectResult = connectResult,
            modifier = modifier,
            showChromeControls = showChromeControls,
            showDeviceHeader = showDeviceHeader,
            showPopOut = showPopOut,
            showContainerChrome = showContainerChrome,
            deviceBorderWidth = deviceBorderWidth,
            deviceCornerRadius = deviceCornerRadius,
            onInput = sendMirrorInput,
            onConnect = ::connect,
        )
    }
}
