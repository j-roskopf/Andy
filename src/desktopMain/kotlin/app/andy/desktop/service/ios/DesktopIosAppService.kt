package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.AndroidActivity
import app.andy.model.AndroidApp
import app.andy.model.AndroidAppDetails
import app.andy.model.AndroidPermission
import app.andy.service.AppService
import app.andy.service.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Simulator app management via `simctl`. Physical devices are out of scope for Phase 1.
 *
 * `simctl listapps` emits an OpenStep plist — always convert with `plutil -convert json`.
 */
class DesktopIosAppService(
    private val runner: CommandRunner,
) : AppService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listApps(serial: String): List<AndroidApp> = withContext(Dispatchers.IO) {
        val plist = runner.run(listOf("xcrun", "simctl", "listapps", serial))
        if (!plist.isSuccess) return@withContext emptyList()
        val temp = File.createTempFile("andy-listapps", ".plist")
        try {
            temp.writeText(plist.stdout)
            val jsonResult = runner.run(listOf("plutil", "-convert", "json", "-o", "-", temp.absolutePath))
            if (!jsonResult.isSuccess) return@withContext emptyList()
            parseListAppsJson(jsonResult.stdout)
        } finally {
            temp.delete()
        }
    }

    internal fun parseListAppsJson(output: String): List<AndroidApp> {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyList()
        return root.mapNotNull { (bundleId, element) ->
            val app = element.jsonObject
            val label = app.string("CFBundleDisplayName")
                ?: app.string("CFBundleName")
                ?: bundleId.substringAfterLast('.')
            val system = app.string("ApplicationType") == "System"
            AndroidApp(
                packageName = bundleId,
                label = label,
                system = system,
                enabled = true,
                versionName = app.string("CFBundleShortVersionString"),
                versionCode = app.string("CFBundleVersion"),
            )
        }.sortedWith(compareBy<AndroidApp> { it.system }.thenBy { it.packageName })
    }

    override suspend fun focusedPackage(serial: String): String? = null

    override suspend fun getAppDetails(serial: String, packageName: String): AndroidAppDetails {
        val apps = listApps(serial)
        val app = apps.firstOrNull { it.packageName == packageName } ?: return AndroidAppDetails()
        return AndroidAppDetails(
            versionName = app.versionName,
            versionCode = app.versionCode,
        )
    }

    override suspend fun launch(serial: String, packageName: String): CommandResult =
        runner.run(listOf("xcrun", "simctl", "launch", serial, packageName))

    override suspend fun launchActivity(serial: String, packageName: String, activityName: String): CommandResult =
        launch(serial, packageName)

    override suspend fun stop(serial: String, packageName: String): CommandResult =
        runner.run(listOf("xcrun", "simctl", "terminate", serial, packageName))

    override suspend fun clearData(serial: String, packageName: String): CommandResult =
        CommandResult.failure("Clear data is not supported for iOS simulators; erase the simulator or delete the app container")

    override suspend fun resetPermissions(serial: String, packageName: String): CommandResult =
        runner.run(listOf("xcrun", "simctl", "privacy", serial, "reset", "all", packageName))

    override suspend fun uninstall(serial: String, packageName: String): CommandResult =
        runner.run(listOf("xcrun", "simctl", "uninstall", serial, packageName))

    override suspend fun install(serial: String, apkPath: String, replace: Boolean): CommandResult =
        runner.run(listOf("xcrun", "simctl", "install", serial, apkPath), timeoutSeconds = 120)

    override suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission> {
        // simctl privacy has no list; expose the known service names as an AndroidPermission-shaped view.
        return IOS_PRIVACY_SERVICES.map { service ->
            AndroidPermission(name = "privacy.$service", granted = null)
        }
    }

    override suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity> = emptyList()

    override suspend fun getIcon(serial: String, packageName: String): ByteArray? = null

    /** Data container path for [packageName], or null if unresolved. */
    suspend fun appDataContainer(serial: String, packageName: String): String? {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", serial, packageName, "data"))
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotBlank() }
    }

    /** Group container paths from listapps GroupContainers. */
    suspend fun groupContainers(serial: String, packageName: String): Map<String, String> {
        val plist = runner.run(listOf("xcrun", "simctl", "listapps", serial))
        if (!plist.isSuccess) return emptyMap()
        val temp = File.createTempFile("andy-listapps", ".plist")
        try {
            temp.writeText(plist.stdout)
            val jsonResult = runner.run(listOf("plutil", "-convert", "json", "-o", "-", temp.absolutePath))
            if (!jsonResult.isSuccess) return emptyMap()
            val root = runCatching { json.parseToJsonElement(jsonResult.stdout).jsonObject }.getOrNull()
                ?: return emptyMap()
            val app = root[packageName]?.jsonObject ?: return emptyMap()
            val groups = app["GroupContainers"]?.jsonObject ?: return emptyMap()
            return groups.mapNotNull { (id, path) ->
                path.jsonPrimitive.contentOrNull?.let { id to it }
            }.toMap()
        } finally {
            temp.delete()
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        val IOS_PRIVACY_SERVICES = listOf(
            "all", "calendar", "contacts-limited", "contacts", "location",
            "media-library", "microphone", "motion", "photos-add", "photos",
            "reminders", "siri", "user-tracking",
        )
    }
}
