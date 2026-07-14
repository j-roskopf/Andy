package app.andy.model

import kotlinx.serialization.Serializable

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
    /** Stable device identity (ro.serialno / mDNS adb instance body). Not the ADB transport serial. */
    val hardwareId: String? = null,
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

@Serializable
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

fun isWirelessAdbSerial(serial: String): Boolean = isWifiIpSerial(serial) || isMdnsAdbSerial(serial)

/**
 * mDNS ADB serials look like `adb-<serial>[-suffix]._adb-tls-connect._tcp`.
 * Returns the instance body after `adb-` when present.
 */
fun extractMdnsHardwareId(serial: String): String? {
    if (!isMdnsAdbSerial(serial)) return null
    val instance = serial.substringBefore("._").trim()
    if (instance.isEmpty()) return null
    val body = when {
        instance.startsWith("adb-", ignoreCase = true) -> instance.drop(4)
        else -> instance
    }.trim()
    return body.takeIf { it.isNotEmpty() }
}

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
 *
 * Only collapse aliases when a stable hardware id is known on both sides — never by
 * model/product alone, which would hide a second phone of the same model.
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
    val leftId = left.hardwareId?.trim().orEmpty()
    val rightId = right.hardwareId?.trim().orEmpty()
    if (leftId.length < 4 || rightId.length < 4) return false
    if (leftId.equals(rightId, ignoreCase = true)) return true
    // mDNS instance bodies often append a short random suffix after ro.serialno.
    return leftId.contains(rightId, ignoreCase = true) || rightId.contains(leftId, ignoreCase = true)
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
