package app.andy.desktop.service.proxy

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class DeviceCaptivePortalSnapshot(
    val serial: String,
    val captivePortalMode: String? = null,
    val captivePortalDetectionEnabled: String? = null,
)

internal object DeviceProxyStateStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun stateFile(proxyDir: File, serial: String): File {
        val safe = serial.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(proxyDir, "device-state-$safe.json")
    }

    fun save(proxyDir: File, snapshot: DeviceCaptivePortalSnapshot) {
        proxyDir.mkdirs()
        stateFile(proxyDir, snapshot.serial).writeText(json.encodeToString(snapshot) + "\n")
    }

    fun load(proxyDir: File, serial: String): DeviceCaptivePortalSnapshot? {
        val file = stateFile(proxyDir, serial)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(DeviceCaptivePortalSnapshot.serializer(), file.readText()) }.getOrNull()
    }

    fun clear(proxyDir: File, serial: String) {
        stateFile(proxyDir, serial).delete()
    }
}
