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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceLivePanel(
    services: AndyServices,
    serial: String?,
    device: AndroidDevice?,
    modifier: Modifier = Modifier,
    showChromeControls: Boolean = true,
    showDeviceHeader: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var mirrorStatus by remember { mutableStateOf("Disconnected") }
    var connectResult by remember { mutableStateOf("") }
    val sendMirrorInput = rememberMirrorInputSender(services, serial)
    LaunchedEffect(services.mirror) {
        services.mirror.status.collectLatest { mirrorStatus = it }
    }
    fun connect() {
        if (serial != null) {
            scope.launch {
                val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
                connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
            }
        }
    }
    LaunchedEffect(serial) {
        connectResult = ""
        if (serial != null) {
            val result = services.mirror.connect(serial, LiveMirrorSettings.config.value)
            connectResult = if (result.isSuccess) result.stdout.ifBlank { "Connected" } else result.stderr
            if (result.isSuccess) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { services.mirror.disconnect() }
                }
            }
        }
    }
    MirrorFrameContent(services.mirror, serial) { frameFlow, frame ->
        LiveDevicePane(
            serial = serial,
            device = device,
            frame = frame,
            frameFlow = frameFlow,
            mirrorStatus = mirrorStatus,
            connectResult = connectResult,
            modifier = modifier,
            showChromeControls = showChromeControls,
            showDeviceHeader = showDeviceHeader,
            onInput = sendMirrorInput,
            onConnect = ::connect,
        )
    }
}
