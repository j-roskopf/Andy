package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.VirtualDevice
import java.io.File

/**
 * Lists AVDs by reading `~/.android/avd` (or `ANDROID_AVD_HOME`) directly.
 *
 * Used when `avdmanager list avd` fails — common for Finder/Dock-launched Andy,
 * which does not inherit shell `JAVA_HOME` and cannot run the Java-based cmdline tools.
 */
object AvdHomeScanner {
    fun avdHome(
        env: Map<String, String> = System.getenv(),
        userHome: File = File(System.getProperty("user.home")),
    ): File {
        val override = env["ANDROID_AVD_HOME"]?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File(userHome, ".android/avd")
    }

    fun listVirtualDevices(
        env: Map<String, String> = System.getenv(),
        userHome: File = File(System.getProperty("user.home")),
    ): List<VirtualDevice> {
        val home = avdHome(env, userHome)
        if (!home.isDirectory) return emptyList()
        return home.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".ini") }
            .mapNotNull { ini -> parseAvdIni(ini) }
            .sortedBy { it.name.lowercase() }
    }

    private fun parseAvdIni(ini: File): VirtualDevice? {
        val props = readIni(ini)
        val name = ini.name.removeSuffix(".ini")
        val path = props["path"]?.takeIf { it.isNotBlank() }
            ?: File(ini.parentFile, "$name.avd").takeIf { it.isDirectory }?.absolutePath
            ?: return null
        val config = File(path, "config.ini").takeIf { it.exists() }?.let(::readIni).orEmpty()
        val target = props["target"]
            ?: config["image.sysdir.1"]
            ?: config["tag.display"]
        val abi = config["abi.type"] ?: config["hw.cpu.arch"]
        val apiLevel = Regex("""android-(\d+)""", RegexOption.IGNORE_CASE)
            .find(target.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""android-(\d+)""", RegexOption.IGNORE_CASE)
                .find(config["image.sysdir.1"].orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        return VirtualDevice(
            name = name,
            path = path,
            target = target,
            abi = abi,
            running = false,
            apiLevel = apiLevel,
            deviceType = AndroidParsers.classifyVirtualDevice(name, target.orEmpty(), config),
            config = config,
        )
    }

    private fun readIni(file: File): Map<String, String> {
        return file.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#") || "=" !in trimmed) null
                else trimmed.substringBefore("=") to trimmed.substringAfter("=")
            }
            .toMap()
    }
}
