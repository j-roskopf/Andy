package app.andy.model

enum class DeviceKind { Physical, Emulator, Unknown }
enum class DeviceConnectionState { Online, Offline, Unauthorized, Missing, Unknown }
enum class DeviceTransport { Usb, Wifi, Unknown }

data class AndroidDevice(
    val serial: String,
    val displayName: String,
    val kind: DeviceKind,
    val state: DeviceConnectionState,
    val transport: DeviceTransport = DeviceTransport.Unknown,
    val apiLevel: String? = null,
    val abi: String? = null,
    val model: String? = null,
    val product: String? = null,
    val batteryPercent: Int? = null,
    val screenSize: String? = null,
    val storageSummary: String? = null,
)

data class MdnsService(
    val instanceName: String,
    val serviceType: String,
    val host: String,
    val port: Int,
) {
    val endpoint: String get() = "$host:$port"
    val isPairing: Boolean get() = serviceType.contains("pairing", ignoreCase = true)
    val isConnect: Boolean
        get() = serviceType.contains("tls-connect", ignoreCase = true) ||
            serviceType == "_adb._tcp" ||
            serviceType.startsWith("_adb._tcp")
}

data class PairedWifiDevice(
    val id: String,
    val displayName: String,
    val mdnsInstanceName: String?,
    val lastEndpoint: String?,
    val pairedAtMillis: Long,
)

private val WifiSerialPattern = Regex("""^\d+\.\d+\.\d+\.\d+:\d+$""")

fun isWifiIpSerial(serial: String): Boolean = WifiSerialPattern.matches(serial)

fun isMdnsAdbSerial(serial: String): Boolean =
    serial.contains("_adb-tls-connect", ignoreCase = true) || serial.contains("._tcp")

fun classifyDeviceKind(serial: String): DeviceKind =
    if (serial.startsWith("emulator-")) DeviceKind.Emulator else DeviceKind.Physical

fun classifyDeviceTransport(serial: String): DeviceTransport = when {
    serial.startsWith("emulator-") -> DeviceTransport.Unknown
    isWifiIpSerial(serial) -> DeviceTransport.Wifi
    isMdnsAdbSerial(serial) -> DeviceTransport.Wifi
    else -> DeviceTransport.Usb
}

/**
 * adb often lists the same wireless device twice: once as `ip:port` (from `adb connect`)
 * and once as `adb-…._adb-tls-connect._tcp` (mDNS). Prefer the IP:port serial.
 */
fun dedupeWifiDeviceAliases(devices: List<AndroidDevice>): List<AndroidDevice> {
    val ipWifi = devices.filter { isWifiIpSerial(it.serial) }
    if (ipWifi.isEmpty()) return devices

    val drop = devices.asSequence()
        .filter { isMdnsAdbSerial(it.serial) }
        .filter { mdns -> ipWifi.any { ip -> sameWifiDeviceIdentity(ip, mdns) } }
        .map { it.serial }
        .toSet()
    if (drop.isEmpty()) return devices
    return devices.filterNot { it.serial in drop }
}

private fun sameWifiDeviceIdentity(left: AndroidDevice, right: AndroidDevice): Boolean {
    val leftModel = left.model?.replace('_', ' ')?.trim()?.lowercase().orEmpty()
    val rightModel = right.model?.replace('_', ' ')?.trim()?.lowercase().orEmpty()
    if (leftModel.isNotEmpty() && leftModel == rightModel) return true

    val leftProduct = left.product?.trim()?.lowercase().orEmpty()
    val rightProduct = right.product?.trim()?.lowercase().orEmpty()
    if (leftProduct.isNotEmpty() && leftProduct == rightProduct) return true

    val leftName = left.displayName.replace('_', ' ').trim().lowercase()
    val rightName = right.displayName.replace('_', ' ').trim().lowercase()
    if (leftName.isNotEmpty() &&
        leftName == rightName &&
        !isWifiIpSerial(leftName) &&
        !isMdnsAdbSerial(leftName)
    ) {
        return true
    }
    return false
}

data class SdkDiscovery(
    val sdkPath: String?,
    val adbPath: String?,
    val emulatorPath: String?,
    val sdkManagerPath: String?,
    val avdManagerPath: String?,
    val issues: List<String> = emptyList(),
) {
    val hasAdb: Boolean get() = adbPath != null
    val hasEmulatorTools: Boolean get() = emulatorPath != null && avdManagerPath != null
}
