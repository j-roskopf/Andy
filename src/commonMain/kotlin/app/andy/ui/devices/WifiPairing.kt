package app.andy.ui.devices

import app.andy.model.AndroidDevice
import app.andy.model.DeviceTransport
import app.andy.model.MdnsService
import app.andy.model.PairedWifiDevice
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.currentTimeMillis
import kotlinx.coroutines.delay

internal fun parseHostPort(value: String): Pair<String, Int>? {
    val trimmed = value.trim()
    val host = trimmed.substringBeforeLast(':').trim()
    val port = trimmed.substringAfterLast(':').trim().toIntOrNull()
    if (host.isBlank() || port == null || port !in 1..65535) return null
    return host to port
}

internal fun findLiveWifiDevice(devices: List<AndroidDevice>, paired: PairedWifiDevice): AndroidDevice? {
    return devices.firstOrNull { device ->
        device.transport == DeviceTransport.Wifi && (
            device.serial == paired.lastEndpoint ||
                (paired.mdnsInstanceName != null && device.serial.contains(paired.mdnsInstanceName, ignoreCase = true)) ||
                device.displayName.equals(paired.displayName, ignoreCase = true)
            )
    } ?: paired.lastEndpoint?.let { endpoint -> devices.firstOrNull { it.serial == endpoint } }
}

internal fun findConnectService(services: List<MdnsService>, instanceName: String?): MdnsService? {
    if (instanceName.isNullOrBlank()) return null
    return services.firstOrNull { it.isConnect && it.instanceName.equals(instanceName, ignoreCase = true) }
        ?: services.firstOrNull { it.isConnect && it.instanceName.contains(instanceName, ignoreCase = true) }
}

internal suspend fun reconnectPairedWifiDevice(
    devices: DeviceService,
    paired: PairedWifiDevice,
    knownServices: List<MdnsService>? = null,
): Pair<CommandResult, String?> {
    val services = knownServices ?: runCatching { devices.listMdnsServices() }.getOrDefault(emptyList())
    val mdnsMatch = findConnectService(services, paired.mdnsInstanceName)
    if (mdnsMatch != null) {
        val result = devices.connect(mdnsMatch.host, mdnsMatch.port)
        if (result.isSuccess) return result to mdnsMatch.endpoint
    }
    val endpoint = paired.lastEndpoint?.let(::parseHostPort)
    if (endpoint != null) {
        val result = devices.connect(endpoint.first, endpoint.second)
        return result to if (result.isSuccess) paired.lastEndpoint else null
    }
    return CommandResult.failure("No mDNS service or saved endpoint for ${paired.displayName}") to null
}

internal suspend fun pairThenConnect(
    devices: DeviceService,
    pairHost: String,
    pairPort: Int,
    code: String,
    preferredConnect: Pair<String, Int>? = null,
    preferredInstanceName: String? = null,
): Pair<CommandResult, PairedWifiDevice?> {
    val pairResult = devices.pair(pairHost, pairPort, code)
    if (!pairResult.isSuccess) return pairResult to null

    delay(800)
    val mdns = runCatching { devices.listMdnsServices() }.getOrDefault(emptyList())
    val connectService = preferredInstanceName?.let { findConnectService(mdns, it) }
        ?: mdns.firstOrNull { it.isConnect && it.host == pairHost }
        ?: mdns.firstOrNull { it.isConnect }
    val connectTarget = preferredConnect
        ?: connectService?.let { it.host to it.port }
    if (connectTarget == null) {
        return CommandResult.failure("Paired, but no connect endpoint was found. Enter IP:port manually.") to null
    }
    val connectResult = devices.connect(connectTarget.first, connectTarget.second)
    if (!connectResult.isSuccess) return connectResult to null

    val endpoint = "${connectTarget.first}:${connectTarget.second}"
    val instanceName = preferredInstanceName
        ?: connectService?.instanceName
        ?: mdns.firstOrNull { it.host == pairHost }?.instanceName
    val paired = PairedWifiDevice(
        id = "wifi-${currentTimeMillis()}-${endpoint.hashCode().toUInt()}",
        displayName = instanceName ?: endpoint,
        mdnsInstanceName = instanceName,
        lastEndpoint = endpoint,
        pairedAtMillis = currentTimeMillis(),
    )
    return CommandResult.success("Paired and connected to $endpoint") to paired
}
