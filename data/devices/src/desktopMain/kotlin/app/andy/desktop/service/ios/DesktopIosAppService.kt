package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.AndroidActivity
import app.andy.model.AndroidApp
import app.andy.model.AndroidAppDetails
import app.andy.model.AndroidPermission
import app.andy.model.IosTargetKind
import app.andy.service.AppService
import app.andy.service.CommandResult
import app.andy.service.IosTargetRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * iOS app management. Simulators go through `simctl`; physical devices (Developer Mode enabled)
 * go through `devicectl`, routed on [IosTargetRegistry]. A serial the registry has never seen is
 * treated as a simulator, since that's the only kind Andy can reach without discovery.
 *
 * `simctl listapps` emits an OpenStep plist — always convert with `plutil -convert json`.
 * `devicectl` writes JSON to a file rather than stdout, hence [runDevicectlJson].
 */
class DesktopIosAppService(
    private val runner: CommandRunner,
) : AppService {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isPhysical(serial: String): Boolean =
        IosTargetRegistry.target(serial)?.kind == IosTargetKind.Physical

    override suspend fun listApps(serial: String): List<AndroidApp> = withContext(Dispatchers.IO) {
        if (isPhysical(serial)) return@withContext listPhysicalApps(serial)
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

    override suspend fun launch(serial: String, packageName: String): CommandResult {
        if (isPhysical(serial)) {
            val response = runDevicectlJson(
                listOf("device", "process", "launch", "--device", serial, packageName),
                timeoutSeconds = 120,
            )
            return response.toCommandResult("Failed to launch $packageName", "Launched $packageName")
        }
        return runner.run(listOf("xcrun", "simctl", "launch", serial, packageName))
    }

    override suspend fun launchActivity(serial: String, packageName: String, activityName: String): CommandResult =
        launch(serial, packageName)

    override suspend fun stop(serial: String, packageName: String): CommandResult {
        if (isPhysical(serial)) return stopPhysical(serial, packageName)
        return runner.run(listOf("xcrun", "simctl", "terminate", serial, packageName))
    }

    override suspend fun clearData(serial: String, packageName: String): CommandResult = if (isPhysical(serial)) {
        CommandResult.failure(
            "Clear data is not supported on physical iOS devices; uninstall and reinstall $packageName to reset its container",
        )
    } else {
        CommandResult.failure("Clear data is not supported for iOS simulators; erase the simulator or delete the app container")
    }

    override suspend fun resetPermissions(serial: String, packageName: String): CommandResult {
        if (isPhysical(serial)) {
            return CommandResult.failure(
                "Resetting privacy permissions is not supported on physical iOS devices; use Settings → General → " +
                    "Transfer or Reset iPhone → Reset → Reset Location & Privacy",
            )
        }
        return runner.run(listOf("xcrun", "simctl", "privacy", serial, "reset", "all", packageName))
    }

    override suspend fun uninstall(serial: String, packageName: String): CommandResult {
        if (isPhysical(serial)) {
            val response = runDevicectlJson(
                listOf("device", "uninstall", "app", "--device", serial, packageName),
                timeoutSeconds = 120,
            )
            return response.toCommandResult("Failed to uninstall $packageName", "Uninstalled $packageName")
        }
        return runner.run(listOf("xcrun", "simctl", "uninstall", serial, packageName))
    }

    override suspend fun install(serial: String, apkPath: String, replace: Boolean): CommandResult {
        if (isPhysical(serial)) {
            val response = runDevicectlJson(
                listOf("device", "install", "app", "--device", serial, apkPath),
                timeoutSeconds = 300,
            )
            return response.toCommandResult("Failed to install $apkPath", "Installed $apkPath")
        }
        return runner.run(listOf("xcrun", "simctl", "install", serial, apkPath), timeoutSeconds = 120)
    }

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

    private suspend fun listPhysicalApps(serial: String): List<AndroidApp> {
        val response = runDevicectlJson(
            listOf("device", "info", "apps", "--device", serial, "--include-all-apps"),
            timeoutSeconds = 60,
        )
        if (!response.result.isSuccess) {
            val detail = IosParsers.parseDevicectlErrorMessage(response.output)
                ?: response.result.stderr.trim().ifBlank { response.result.stdout.trim() }
                    .ifBlank { "devicectl device info apps failed" }
            throw IllegalStateException(detail)
        }
        return IosParsers.parseDevicectlApps(response.output)
    }

    /**
     * `devicectl` has no terminate-by-bundle-id, only terminate-by-pid, so this resolves the pid
     * from the running process list first. Process entries expose an executable path rather than
     * a bundle identifier, so the bundle's own `.app` directory name (from `info apps`) is the
     * matching key when the identifier itself doesn't appear in the path.
     */
    private suspend fun stopPhysical(serial: String, packageName: String): CommandResult {
        val processes = runDevicectlJson(
            listOf("device", "info", "processes", "--device", serial),
            timeoutSeconds = 60,
        )
        if (!processes.result.isSuccess) {
            return processes.toCommandResult("Failed to list processes on $serial", "")
        }
        val executableHint = physicalAppExecutableHint(serial, packageName)
        val pid = findProcessId(processes.output, packageName, executableHint)
            ?: return CommandResult.failure("Could not find a running process for $packageName on $serial")
        val terminate = runDevicectlJson(
            listOf("device", "process", "terminate", "--device", serial, "--pid", pid.toString()),
            timeoutSeconds = 60,
        )
        return terminate.toCommandResult("Failed to stop $packageName", "Stopped $packageName")
    }

    /** The `<Name>.app` directory name for [packageName], used to match process executable paths. */
    private suspend fun physicalAppExecutableHint(serial: String, packageName: String): String? {
        val response = runDevicectlJson(
            listOf("device", "info", "apps", "--device", serial, "--include-all-apps"),
            timeoutSeconds = 60,
        )
        if (!response.result.isSuccess) return null
        val apps = runCatching {
            json.parseToJsonElement(response.output).jsonObject["result"]?.jsonObject?.get("apps")?.jsonArray
        }.getOrNull() ?: return null
        val url = apps.firstNotNullOfOrNull { element ->
            val app = runCatching { element.jsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (app.string("bundleIdentifier") != packageName) return@firstNotNullOfOrNull null
            app.string("url")
        } ?: return null
        return url.trimEnd('/').substringAfterLast('/').takeIf { it.endsWith(".app") }
    }

    internal fun findProcessId(output: String, packageName: String, executableHint: String?): Int? {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return null
        val result = root["result"]?.jsonObject ?: root
        val processes = (result["runningProcesses"] ?: result["processes"])
            ?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        val candidates = processes.mapNotNull { element ->
            val process = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val pid = process["processIdentifier"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: process["pid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: return@mapNotNull null
            pid to (process.string("executable") ?: process.string("path")).orEmpty()
        }
        return candidates.firstOrNull { (_, executable) -> executable.contains(packageName) }?.first
            ?: executableHint?.let { hint ->
                candidates.firstOrNull { (_, executable) -> executable.contains("/$hint/") }?.first
            }
    }

    private suspend fun runDevicectlJson(args: List<String>, timeoutSeconds: Long): DevicectlResponse =
        runner.runDevicectlJson(args, timeoutSeconds)

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
