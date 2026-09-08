package app.andy.desktop.service

import app.andy.model.DeviceConnectionState
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.DeviceService
import app.andy.service.IosDeviceService
import app.andy.service.IosTargetRegistry
import app.andy.service.WorkspaceStore

/**
 * Resolves MCP `serial` args across Android devices and iOS targets (UDID).
 * Keeps [IosTargetRegistry] fresh so routed services see the same UDIDs.
 */
internal class McpTargetResolver(
    private val devices: DeviceService,
    private val iosDevices: IosDeviceService,
    private val workspaceStore: WorkspaceStore,
) {
    suspend fun refreshIosTargets(): List<IosTarget> {
        val targets = runCatching { iosDevices.listTargets() }.getOrDefault(emptyList())
        IosTargetRegistry.update(targets)
        return targets
    }

    fun isIos(id: String): Boolean = IosTargetRegistry.isIosTarget(id)

    fun isPhysicalIos(id: String): Boolean =
        IosTargetRegistry.target(id)?.kind == IosTargetKind.Physical

    suspend fun resolve(explicit: String?): String {
        val iosTargets = refreshIosTargets()
        val androidOnline = devices.listDevices().filter { it.state == DeviceConnectionState.Online }
        val iosReachable = iosTargets.filter { it.isMcpReachable }

        if (!explicit.isNullOrBlank()) {
            val knownAndroid = androidOnline.any { it.serial == explicit } ||
                devices.listDevices().any { it.serial == explicit }
            val knownIos = iosTargets.any { it.udid == explicit }
            if (knownAndroid || knownIos) return explicit
            throw IllegalArgumentException(
                "Unknown target '$explicit'. Available: [${formatAvailable(androidOnline.map { it.serial to it.model }, iosReachable)}]",
            )
        }

        val saved = workspaceStore.load().selectedDeviceSerial
        if (!saved.isNullOrBlank()) {
            if (androidOnline.any { it.serial == saved } || iosReachable.any { it.udid == saved }) {
                return saved
            }
        }

        val allIds = androidOnline.map { it.serial } + iosReachable.map { it.udid }
        when (allIds.size) {
            1 -> return allIds.first()
            0 -> throw IllegalArgumentException(
                "No online Android or iOS targets found. Launch an emulator/simulator or connect a device.",
            )
            else -> throw IllegalArgumentException(
                "Multiple targets available; specify 'serial'. Available: [${formatAvailable(androidOnline.map { it.serial to it.model }, iosReachable)}]",
            )
        }
    }

    /** Like [resolve], but rejects iOS UDIDs for Android-only tools. */
    suspend fun resolveAndroid(explicit: String?): String {
        val id = resolve(explicit)
        if (isIos(id)) {
            throw IllegalArgumentException("This tool is Android-only; '$id' is an iOS target")
        }
        return id
    }

    private fun formatAvailable(
        android: List<Pair<String, String?>>,
        ios: List<IosTarget>,
    ): String {
        val parts = buildList {
            android.forEach { (serial, model) -> add("$serial (android, ${model ?: "unknown"})") }
            ios.forEach { t -> add("${t.udid} (ios ${t.kind.name.lowercase()}, ${t.displayName})") }
        }
        return parts.joinToString(", ")
    }
}

private val IosTarget.isMcpReachable: Boolean
    get() = when (kind) {
        IosTargetKind.Simulator -> state == IosTargetState.Booted
        IosTargetKind.Physical -> state != IosTargetState.Unavailable
    }
