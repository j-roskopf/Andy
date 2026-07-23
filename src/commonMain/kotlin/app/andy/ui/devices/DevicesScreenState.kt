package app.andy.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.VirtualDevice
import app.andy.service.AvdService
import app.andy.ui.components.PendingConfirmation

internal class DevicesScreenState(
    val avd: AvdService,
) {
    var avds by mutableStateOf<List<VirtualDevice>>(emptyList())
    var avdStatus by mutableStateOf("")
    var startingAvd by mutableStateOf<String?>(null)
    var deviceQuery by mutableStateOf("")
    var deviceFilter by mutableStateOf(DeviceListFilter.All)
    var platformTab by mutableStateOf(DevicesPlatformTab.Android)
    var showCreateWizard by mutableStateOf(false)
    var showPairDialog by mutableStateOf(false)
    var wifiStatus by mutableStateOf("")
    var pendingConfirmation by mutableStateOf<PendingConfirmation?>(null)
    var cloneSource by mutableStateOf<VirtualDevice?>(null)
}
