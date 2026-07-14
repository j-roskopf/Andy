package app.andy.model

enum class SystemImageBadge(val label: String) { PlayStore("Play"), Wear("Wear"), Tv("TV"), Automotive("Auto") }

data class SystemImage(
    val packageId: String,
    val api: String,
    val variant: String,
    val abi: String,
    val displayName: String,
    val installed: Boolean,
    val sizeOnDisk: Long = 0L,
) {
    val apiLevel: Int get() = api.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    val badges: List<SystemImageBadge> get() {
        val v = variant.lowercase()
        return buildList {
            if (v.contains("playstore")) add(SystemImageBadge.PlayStore)
            if (v.contains("wear")) add(SystemImageBadge.Wear)
            if (v.contains("tv")) add(SystemImageBadge.Tv)
            if (v.contains("automotive")) add(SystemImageBadge.Automotive)
        }
    }
}

enum class AvdProfileCategory { Phone, Foldable, Tablet, Watch, Tv, Automotive, Desktop, Other }

data class AvdProfile(
    val id: String,
    val name: String,
    val oem: String?,
    val tag: String?,
    val resolution: String?,
    val density: String?,
    val category: AvdProfileCategory = AvdProfileCategory.Other,
)

enum class VirtualDeviceType { Phone, Foldable, Tablet, Watch, Tv, Automotive, Desktop, Unknown }

data class VirtualDevice(
    val name: String,
    val path: String?,
    val target: String?,
    val abi: String?,
    val running: Boolean,
    val apiLevel: Int? = null,
    val deviceType: VirtualDeviceType = VirtualDeviceType.Unknown,
    val config: Map<String, String> = emptyMap(),
    /** The renderer selected by the most recent emulator launch, read from its launch log. */
    val graphicsBackend: String? = null,
    val graphicsRenderer: String? = null,
    /** True only when the launch log identifies a known software graphics implementation. */
    val graphicsSoftwareRendered: Boolean = false,
)

enum class AvdCameraOption(val configValue: String) {
    None("none"),
    Emulated("emulated"),
    Webcam0("webcam0"),
}

data class AvdCreationConfig(
    val name: String,
    val profileId: String,
    val systemImagePackage: String,
    val orientation: String = "portrait",
    val ramMb: Int? = null,
    val storageMb: Int? = null,
    val cpuCores: Int? = null,
    val gpuMode: String = "auto",
    val backCamera: AvdCameraOption = AvdCameraOption.Emulated,
    val frontCamera: AvdCameraOption = AvdCameraOption.None,
    val locale: String = "",
    val hardwareKeyboard: Boolean = true,
    val startAfterCreate: Boolean = false,
)

data class EmulatorSnapshot(
    val name: String,
    val avdName: String,
    val source: String = "",
    val size: String? = null,
    val createdTime: String? = null,
    val screenshotPath: String? = null,
    val compatible: Boolean = true,
)
