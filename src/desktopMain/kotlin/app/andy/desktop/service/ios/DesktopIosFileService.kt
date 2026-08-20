package app.andy.desktop.service.ios

import app.andy.desktop.service.CommandRunner
import app.andy.model.DeviceFile
import app.andy.service.CommandResult
import app.andy.service.FileService
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
 */
class DesktopIosFileService(
    private val runner: CommandRunner,
    private val simulatorDevicesRoot: File = File(
        System.getProperty("user.home"),
        "Library/Developer/CoreSimulator/Devices",
    ),
) : FileService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun list(serial: String, path: String): List<DeviceFile> = withContext(Dispatchers.IO) {
        val normalized = path.trim()
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

    /** Data container path for [bundleId], or null if unresolved. */
    suspend fun appDataContainer(serial: String, bundleId: String): String? {
        val result = runner.run(listOf("xcrun", "simctl", "get_app_container", serial, bundleId, "data"))
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotBlank() }
    }

    private suspend fun listContainerRoots(serial: String): List<DeviceFile> {
        val containers = fetchAppContainers(serial)
        val appRoots = containers.mapNotNull { container ->
            val containerPath = container.dataContainerPath ?: return@mapNotNull null
            val dir = File(containerPath)
            if (!dir.isDirectory) return@mapNotNull null
            dir.toDeviceFile(displayName = container.displayName)
        }
        val groupRoots = containers.flatMap { container ->
            container.groupContainers.mapNotNull { (groupId, groupPath) ->
                val dir = File(groupPath)
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
