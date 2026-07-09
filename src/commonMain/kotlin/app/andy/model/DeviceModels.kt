package app.andy.model

enum class DeviceKind { Physical, Emulator, Unknown }
enum class DeviceConnectionState { Online, Offline, Unauthorized, Missing, Unknown }

data class AndroidDevice(
    val serial: String,
    val displayName: String,
    val kind: DeviceKind,
    val state: DeviceConnectionState,
    val apiLevel: String? = null,
    val abi: String? = null,
    val model: String? = null,
    val product: String? = null,
    val batteryPercent: Int? = null,
    val screenSize: String? = null,
    val storageSummary: String? = null,
)

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
