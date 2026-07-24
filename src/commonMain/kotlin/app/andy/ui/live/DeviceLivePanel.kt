package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.andy.model.AndroidDevice
import app.andy.service.AndyServices
import app.andy.service.MirrorSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceLivePanel(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    displayName: String? = device?.displayName,
    modifier: Modifier = Modifier,
    showChromeControls: Boolean = true,
    showDeviceHeader: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var mirrorSession by remember { mutableStateOf<MirrorSession?>(null) }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    LaunchedEffect(services.mirror) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    LaunchedEffect(services.mirror, serial) {
        services.mirror.session.collectLatest { session ->
            mirrorSession = session?.takeIf { it.serial == serial }
        }
    }
    fun connect() {
        if (serial != null) {
            scope.launch {
                val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
                connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
            }
        }
    }
    // Connect on enter; leave the session warm when the panel leaves composition so Live /
    // Design / Performance handoffs stay instant (same pattern as DesignScreen).
    LaunchedEffect(serial) {
        connectResult = ""
        if (serial != null) {
            val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
            connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
        } else {
            withContext(NonCancellable) {
                services.mirror.disconnect()
            }
        }
    }
    MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
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
            onInput = sendMirrorInput,
            onConnect = ::connect,
        )
    }
}
