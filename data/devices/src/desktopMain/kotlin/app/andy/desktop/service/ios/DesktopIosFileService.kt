package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.CommandRunner
import app.andy.model.DeviceFile
import app.andy.model.IosTargetKind
import app.andy.service.CommandResult
import app.andy.service.FileService
import app.andy.service.IosTargetRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * iOS Files & data (Phase 2.1). Simulator app containers are ordinary host directories under
 * `~/Library/Developer/CoreSimulator/Devices/<udid>/data/Containers/Data/Application/<uuid>/` —
 * resolve the root with `simctl get_app_container`, then it's plain [java.io.File] access, no
 * pull/push/run-as required.
 *
 * The synthetic root (blank/"/" path) lists each installed app's data container plus any
 * app-group containers as extra top-level roots, since there's no single filesystem root that
 * makes sense for a simulator the way `/sdcard` does for Android.
 *
 * Physical devices (Developer Mode enabled) have no host-visible container, so they go through
 * `devicectl device info files` / `device copy`. Their paths are synthetic too, but shaped
 * `/<bundleId>/<path within the app data container>` since devicectl addresses files by
 * (domain, bundle identifier, relative subdirectory) rather than by absolute path.
 */
class DesktopIosFileService(
    private val runner: CommandRunner,
    private val simulatorDevicesRoot: File = File(
        System.getProperty("user.home"),
        "Library/Developer/CoreSimulator/Devices",
    ),
) : FileService {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isPhysical(serial: String): Boolean =
        IosTargetRegistry.target(serial)?.kind == IosTargetKind.Physical

    override suspend fun list(serial: String, path: String): List<DeviceFile> = withContext(Dispatchers.IO) {
        val normalized = path.trim()
        if (isPhysical(serial)) return@withContext listPhysical(serial, normalized)
        if (normalized.isBlank() || normalized == "/" || normalized == "/sdcard") {
            return@withContext listContainerRoots(serial)
        }
        runCatching {
            val dir = requireUnderSimulator(serial, normalized)
            dir.listFiles()
                ?.map { it.toDeviceFile() }
                ?.sortedWith(compareByDescending<DeviceFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult =
        withContext(Dispatchers.IO) {
            if (isPhysical(serial)) return@withContext pullPhysical(serial, remotePath, localPath)
            runCatching {
                val source = requireUnderSimulator(serial, remotePath)
                require(source.exists()) { "$remotePath does not exist" }
                val target = File(localPath)
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = true)
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                CommandResult.success("Pulled $remotePath to $localPath")
            }.getOrElse { CommandResult.failure(it.message ?: "Pull failed") }
        }

    override suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult =
        withContext(Dispatchers.IO) {
            if (isPhysical(serial)) return@withContext pushPhysical(serial, localPath, remotePath)
            runCatching {
                val source = File(localPath)
                require(source.exists()) { "$localPath does not exist" }
                val target = requireUnderSimulator(serial, remotePath)
                if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = true)
                } else {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                CommandResult.success("Pushed $localPath to $remotePath")
            }.getOrElse { CommandResult.failure(it.message ?: "Push failed") }
        }

    override suspend fun delete(serial: String, remotePath: String): CommandResult = withContext(Dispatchers.IO) {
        if (isPhysical(serial)) {
            return@withContext CommandResult.failure(
                "Deleting files is not supported on physical iOS devices; devicectl can only copy files in and out",
            )
        }
        runCatching {
            val target = requireUnderSimulator(serial, remotePath)
            require(target.exists()) { "$remotePath does not exist" }
            val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (!ok) error("Failed to delete $remotePath")
            CommandResult.success("Deleted $remotePath")
        }.getOrElse { CommandResult.failure(it.message ?: "Delete failed") }
    }

    /**
     * Simulator file ops must stay under that UDID's CoreSimulator device tree. The Files UI can
     * still type an absolute path; rejecting anything outside the selected simulator prevents
     * browsing/deleting arbitrary host files.
     */
    private fun requireUnderSimulator(serial: String, remotePath: String): File {
        val udid = serial.trim()
        require(udid.isNotEmpty()) { "Missing simulator UDID" }
        val root = File(simulatorDevicesRoot, udid).canonicalFile
        val target = File(remotePath).canonicalFile
        val rootPath = root.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path is outside the selected simulator"
        }
        return target
    }

    private suspend fun listPhysical(serial: String, path: String): List<DeviceFile> {
        val location = PhysicalPath.parse(path) ?: return listPhysicalAppRoots(serial)
        val args = buildList {
            addAll(listOf("device", "info", "files", "--device", serial))
            addAll(listOf("--domain-type", "appDataContainer", "--domain-identifier", location.bundleId))
            if (location.subdirectory.isNotBlank()) addAll(listOf("--subdirectory", location.subdirectory))
            add("--no-recurse")
        }
        val response = runner.runDevicectlJson(args)
        if (!response.result.isSuccess) return emptyList()
        return IosParsers.parseDevicectlFiles(response.output)
            .map { it.copy(path = location.child(it.path)) }
    }

    /**
     * A physical device exposes no browsable root, so each app with a readable data container
     * becomes a synthetic top-level directory named after its bundle identifier.
     */
    private suspend fun listPhysicalAppRoots(serial: String): List<DeviceFile> {
        val response = runner.runDevicectlJson(
            listOf("device", "info", "apps", "--device", serial, "--include-all-apps"),
        )
        if (!response.result.isSuccess) return emptyList()
        return IosParsers.parseDevicectlApps(response.output).map { app ->
            DeviceFile(
                path = "/${app.packageName}",
                name = app.label?.takeIf { it.isNotBlank() } ?: app.packageName,
                isDirectory = true,
                sizeBytes = null,
                permissions = null,
                modified = null,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private suspend fun pullPhysical(serial: String, remotePath: String, localPath: String): CommandResult {
        val location = PhysicalPath.parse(remotePath)
            ?: return CommandResult.failure("Select a file inside an app container to pull")
        val response = runner.runDevicectlJson(
            listOf(
                "device", "copy", "from", "--device", serial,
                "--domain-type", "appDataContainer", "--domain-identifier", location.bundleId,
                "--source", location.subdirectory, "--destination", localPath,
            ),
            timeoutSeconds = 300,
        )
        return response.toCommandResult("Failed to pull $remotePath", "Pulled $remotePath to $localPath")
    }

    private suspend fun pushPhysical(serial: String, localPath: String, remotePath: String): CommandResult {
        val location = PhysicalPath.parse(remotePath)
            ?: return CommandResult.failure("Select a destination inside an app container to push")
        val response = runner.runDevicectlJson(
            listOf(
                "device", "copy", "to", "--device", serial,
                "--domain-type", "appDataContainer", "--domain-identifier", location.bundleId,
                "--source", localPath, "--destination", location.subdirectory,
            ),
            timeoutSeconds = 300,
        )
        return response.toCommandResult("Failed to push $localPath", "Pushed $localPath to $remotePath")
    }

    /** Data container path for [bundleId], or null if unresolved. */
    suspend fun appDataContainer(serial: String, bundleId: String): String? {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", serial, bundleId, "data"))
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotBlank() }
    }

    private suspend fun listContainerRoots(serial: String): List<DeviceFile> {
        val containers = fetchAppContainers(serial)
        val appRoots = containers.mapNotNull { container ->
            val containerPath = container.dataContainerPath?.let { hostPathFromSimctl(it) } ?: return@mapNotNull null
            val dir = File(containerPath)
            if (!dir.isDirectory) return@mapNotNull null
            dir.toDeviceFile(displayName = container.displayName)
        }
        val groupRoots = containers.flatMap { container ->
            container.groupContainers.mapNotNull { (groupId, groupPath) ->
                val dir = File(hostPathFromSimctl(groupPath))
                if (!dir.isDirectory) return@mapNotNull null
                dir.toDeviceFile(displayName = "Group: $groupId")
            }
        }.distinctBy { it.path }
        return (appRoots + groupRoots).sortedBy { it.name.lowercase() }
    }

    private suspend fun fetchAppContainers(serial: String): List<IosAppContainer> {
        val plist = runner.run(listOf("xcrun", "simctl", "listapps", serial))
        if (!plist.isSuccess) return emptyList()
        val temp = File.createTempFile("andy-listapps", ".plist")
        return try {
            temp.writeText(plist.stdout)
            val jsonResult = runner.run(listOf("plutil", "-convert", "json", "-o", "-", temp.absolutePath))
            if (!jsonResult.isSuccess) return emptyList()
            parseAppContainersJson(jsonResult.stdout)
        } finally {
            temp.delete()
        }
    }

    internal fun parseAppContainersJson(output: String): List<IosAppContainer> {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return emptyList()
        return root.map { (bundleId, element) ->
            val app = element.jsonObject
            val displayName = app["CFBundleDisplayName"]?.jsonPrimitive?.contentOrNull
                ?: app["CFBundleName"]?.jsonPrimitive?.contentOrNull
                ?: bundleId
            val dataContainer = app["DataContainer"]?.jsonPrimitive?.contentOrNull
            val groupContainers = app["GroupContainers"]?.jsonObject?.mapNotNull { (id, path) ->
                path.jsonPrimitive.contentOrNull?.let { id to it }
            }?.toMap().orEmpty()
            IosAppContainer(
                bundleId = bundleId,
                displayName = displayName,
                dataContainerPath = dataContainer,
                groupContainers = groupContainers,
            )
        }
    }

    private fun File.toDeviceFile(displayName: String? = null): DeviceFile {
        val perms = runCatching {
            val posix = Files.getPosixFilePermissions(toPath())
            (if (isDirectory) "d" else "-") + PosixFilePermissions.toString(posix)
        }.getOrDefault(if (isDirectory) "d---------" else "----------")
        val modified = runCatching { DateFormat.format(Date(lastModified())) }.getOrNull()
        return DeviceFile(
            path = absolutePath,
            name = displayName ?: name,
            isDirectory = isDirectory,
            sizeBytes = if (isFile) length() else null,
            permissions = perms,
            modified = modified,
        )
    }

    companion object {
        private val DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}

internal data class IosAppContainer(
    val bundleId: String,
    val displayName: String,
    val dataContainerPath: String?,
    val groupContainers: Map<String, String>,
)

/** simctl listapps emits `file:///…` URLs; [java.io.File] needs a plain filesystem path. */
internal fun hostPathFromSimctl(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("file://") -> {
            runCatching { java.net.URI(trimmed).path }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: trimmed.removePrefix("file://")
        }
        else -> trimmed
    }
}

/**
 * A physical-device browse path, split into the devicectl addressing pair: the app whose data
 * container is being read, and the path relative to that container's root.
 */
internal data class PhysicalPath(val bundleId: String, val subdirectory: String) {
    /**
     * Re-prefixes a path returned by devicectl back into Andy's browse space. Entries come back
     * relative to the container root on some Xcode versions and relative to `--subdirectory` on
     * others, so only prepend the subdirectory when it isn't already there.
     */
    fun child(relativePath: String): String {
        val relative = relativePath.trimStart('/')
        val full = when {
            subdirectory.isBlank() -> relative
            relative == subdirectory || relative.startsWith("$subdirectory/") -> relative
            else -> "$subdirectory/$relative"
        }
        return "/$bundleId/$full".trimEnd('/')
    }

    companion object {
        /** Null for the synthetic root, which lists apps rather than files. */
        fun parse(path: String): PhysicalPath? {
            val trimmed = path.trim().trim('/')
            if (trimmed.isEmpty() || trimmed == "sdcard") return null
            val bundleId = trimmed.substringBefore('/')
            return PhysicalPath(bundleId, trimmed.substringAfter('/', missingDelimiterValue = ""))
        }
    }
}
