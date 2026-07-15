package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.AndroidActivity
import app.andy.model.AndroidApp
import app.andy.model.AndroidAppDetails
import app.andy.model.AndroidPermission
import app.andy.service.AppService
import app.andy.service.CommandResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DesktopAppService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : AppService {
    override suspend fun listApps(serial: String): List<AndroidApp> {
        val packages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-U", "--show-versioncode")).stdout
            .lineSequence()
            .mapNotNull { line ->
                val packageName = Regex("""package:([^\s]+)""").find(line)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val versionCode = Regex("""versionCode:([^\s]+)""").find(line)?.groupValues?.getOrNull(1)
                packageName to versionCode
            }
            .toList()
        val systemPackages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-s")).stdout
            .lineSequence()
            .mapNotNull { it.substringAfter("package:", "").takeIf(String::isNotBlank)?.trim() }
            .toSet()
        val disabledPackages = devices.shell(serial, listOf("cmd", "package", "list", "packages", "-d")).stdout
            .lineSequence()
            .mapNotNull { it.substringAfter("package:", "").takeIf(String::isNotBlank)?.trim() }
            .toSet()
        val appLabels = queryAppLabels(serial)
        return packages
            .map { (packageName, versionCode) ->
                AndroidApp(
                    packageName = packageName,
                    label = appLabels[packageName] ?: packageName.substringAfterLast('.'),
                    system = packageName in systemPackages,
                    enabled = packageName !in disabledPackages,
                    versionCode = versionCode,
                )
            }
            .sortedWith(compareBy<AndroidApp> { it.system }.thenBy { it.packageName })
    }

    override suspend fun getAppDetails(serial: String, packageName: String): AndroidAppDetails {
        val result = devices.shell(serial, listOf("dumpsys", "package", packageName))
        return if (result.isSuccess) AndroidParsers.parseAppDetails(result.stdout) else AndroidAppDetails()
    }

    private fun getHelperFile(): File? {
        val target = File(System.getProperty("user.home"), ".andy/helper/andy-helper.jar")
        if (System.getProperty("andy.helper.extracted") == "true" && target.isFile && target.length() > 0) {
            return target
        }
        val resource = javaClass.classLoader.getResourceAsStream("andy-helper.jar") ?: return null
        target.parentFile.mkdirs()
        try {
            resource.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setReadable(true, false)
            System.setProperty("andy.helper.extracted", "true")
        } catch (_: Exception) {
            if (!target.isFile || target.length() == 0L) return null
        }
        return target.takeIf { it.isFile && it.length() > 0 }
    }

    private suspend fun ensureHelperPushed(serial: String, helperFile: File): Boolean {
        val adb = devices.adbPath() ?: return false
        val pushedKey = "andy.helper.pushed.$serial"
        if (System.getProperty(pushedKey) == "true") return true
        val remoteHelper = "/data/local/tmp/andy-helper.jar"
        val pushed = runner.run(listOf(adb, "-s", serial, "push", helperFile.absolutePath, remoteHelper)).isSuccess
        if (pushed) {
            System.setProperty(pushedKey, "true")
        }
        return pushed
    }

    private suspend fun queryAppLabels(serial: String): Map<String, String> {
        val helperFile = getHelperFile()
        if (helperFile != null) {
            val remoteHelper = "/data/local/tmp/andy-helper.jar"
            if (ensureHelperPushed(serial, helperFile)) {
                val result = devices.shell(serial, listOf("CLASSPATH=$remoteHelper", "app_process", "/", "app.andy.helper.Helper", "list"))
                if (result.isSuccess && result.stdout.isNotBlank()) {
                    val labels = mutableMapOf<String, String>()
                    result.stdout.lineSequence().forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            labels[parts[0]] = parts[1]
                        }
                    }
                    return labels
                }
            }
        }

        val output = devices.shell(serial, listOf("dumpsys", "package")).stdout
        if (output.isBlank()) return emptyMap()

        val labels = mutableMapOf<String, String>()
        var currentPackage: String? = null
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            Regex("""Package \[([^\]]+)]""").find(line)?.let { match ->
                currentPackage = match.groupValues[1]
                return@forEach
            }

            val packageName = currentPackage ?: return@forEach
            val label = Regex("""application-label(?:-[^:]+)?:'([^']*)'""")
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach

            labels.putIfAbsent(packageName, label)
        }
        return labels
    }

    override suspend fun launch(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"))
    }

    override suspend fun launchActivity(serial: String, packageName: String, activityName: String): CommandResult {
        val componentName = if (activityName.startsWith('.') || activityName.contains('.')) activityName else ".${activityName}"
        return devices.shell(serial, listOf("am", "start", "-n", "$packageName/$componentName"))
    }

    override suspend fun stop(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("am", "force-stop", packageName))
    }

    override suspend fun clearData(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("pm", "clear", packageName))
    }

    override suspend fun resetPermissions(serial: String, packageName: String): CommandResult {
        return devices.shell(serial, listOf("pm", "reset-permissions", packageName))
    }

    override suspend fun uninstall(serial: String, packageName: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "uninstall", packageName), 60)
    }

    override suspend fun install(serial: String, apkPath: String, replace: Boolean): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        val command = buildList {
            add(adb)
            add("-s")
            add(serial)
            add("install")
            if (replace) add("-r")
            add(apkPath)
        }
        return runner.run(command, 180)
    }

    override suspend fun listPermissions(serial: String, packageName: String): List<AndroidPermission> {
        val output = devices.shell(serial, listOf("dumpsys", "package", packageName)).stdout
        return AndroidParsers.parsePackagePermissions(output)
    }

    override suspend fun listActivities(serial: String, packageName: String): List<AndroidActivity> {
        val output = devices.shell(serial, listOf("dumpsys", "package", packageName)).stdout
        return AndroidParsers.parsePackageActivities(packageName, output)
    }

    override suspend fun getIcon(serial: String, packageName: String): ByteArray? {
        val helperFile = getHelperFile() ?: return null
        val remoteHelper = "/data/local/tmp/andy-helper.jar"
        if (!ensureHelperPushed(serial, helperFile)) return null

        val result = devices.shell(serial, listOf("CLASSPATH=$remoteHelper", "app_process", "/", "app.andy.helper.Helper", "icon", packageName))
        if (result.isSuccess) {
            val base64 = result.stdout.trim()
            if (base64.isNotEmpty()) {
                return try {
                    java.util.Base64.getDecoder().decode(base64)
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }
}
