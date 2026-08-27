package app.andy.desktop.service

import app.andy.model.SdkDiscovery
import java.io.File

class SdkLocator {
    fun discover(preferredPath: String? = null): SdkDiscovery {
        val candidates = buildList {
            preferredPath?.takeIf { it.isNotBlank() }?.let(::add)
            System.getenv("ANDROID_HOME")?.let(::add)
            System.getenv("ANDROID_SDK_ROOT")?.let(::add)
            add(File(System.getProperty("user.home"), "Library/Android/sdk").absolutePath)
            add(File(System.getProperty("user.home"), "Android/Sdk").absolutePath)
            add(File(System.getProperty("user.home"), "AppData/Local/Android/Sdk").absolutePath)
        }.distinct()

        val sdkRoot = candidates.map(::File).firstOrNull { it.exists() && it.isDirectory }
        val issues = mutableListOf<String>()
        if (sdkRoot == null) {
            issues += "No Android SDK found. Install Android Studio or Android command-line tools."
            return SdkDiscovery(null, null, null, null, null, issues)
        }

        fun exe(name: String): String = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "$name.bat" else name
        fun existing(vararg relative: String): String? = relative
            .map { File(sdkRoot, it) }
            .firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath

        val adb = existing("platform-tools/${exe("adb")}", "platform-tools/adb.exe")
        val emulator = existing("emulator/${exe("emulator")}", "emulator/emulator.exe")
        val cmdlineBin = File(sdkRoot, "cmdline-tools")
            .takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && (it.name == exe("sdkmanager") || it.name == "sdkmanager.bat") }
            ?.firstOrNull()
            ?.parentFile
        val sdkManager = cmdlineBin?.resolve(exe("sdkmanager"))?.takeIf { it.exists() }?.absolutePath
            ?: cmdlineBin?.resolve("sdkmanager.bat")?.takeIf { it.exists() }?.absolutePath
        val avdManager = cmdlineBin?.resolve(exe("avdmanager"))?.takeIf { it.exists() }?.absolutePath
            ?: cmdlineBin?.resolve("avdmanager.bat")?.takeIf { it.exists() }?.absolutePath

        if (adb == null) issues += "Missing platform-tools/adb in ${sdkRoot.absolutePath}."
        if (emulator == null) issues += "Missing emulator binary in ${sdkRoot.absolutePath}."
        if (sdkManager == null || avdManager == null) issues += "Missing Android SDK command-line tools; install cmdline-tools to enable catalog and AVD creation."

        return SdkDiscovery(
            sdkPath = sdkRoot.absolutePath,
            adbPath = adb,
            emulatorPath = emulator,
            sdkManagerPath = sdkManager,
            avdManagerPath = avdManager,
            issues = issues,
        )
    }
}

