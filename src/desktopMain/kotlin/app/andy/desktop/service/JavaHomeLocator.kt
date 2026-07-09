package app.andy.desktop.service

import java.io.File

/**
 * Finds a host JDK/JRE for Android cmdline-tools (`avdmanager`, `sdkmanager`).
 *
 * Packaged Andy is often launched from Finder/Dock with a minimal environment and no
 * `JAVA_HOME`. Andy's own jlink runtime also may not ship a `java` launcher, so those
 * shell scripts fail unless we point them at Android Studio's JBR or another installed JDK.
 */
object JavaHomeLocator {
    fun find(
        env: Map<String, String> = System.getenv(),
        javaHomeProperty: String? = System.getProperty("java.home"),
        userHome: File = File(System.getProperty("user.home")),
        applications: File = File("/Applications"),
        runCommand: (List<String>) -> String? = ::captureCommandOutput,
    ): String? {
        val candidates = buildList {
            env["JAVA_HOME"]?.takeIf { it.isNotBlank() }?.let(::add)
            javaHomeProperty?.takeIf { it.isNotBlank() }?.let(::add)
            add(File(userHome, ".sdkman/candidates/java/current").absolutePath)
            addAll(sdkmanJavaHomes(userHome))
            addAll(androidStudioJavaHomes(applications))
            addAll(androidStudioJavaHomes(File(userHome, "Applications")))
            addAll(homebrewJavaHomes())
            runCommand(listOf("/usr/libexec/java_home"))?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        }

        return candidates
            .map { it.trim().trimEnd('/') }
            .distinct()
            .firstOrNull(::isUsableJavaHome)
    }

    fun isUsableJavaHome(path: String): Boolean {
        val home = File(path)
        if (!home.isDirectory) return false
        val java = File(home, "bin/java")
        val javaExe = File(home, "bin/java.exe")
        return (java.exists() && java.canExecute()) || javaExe.exists()
    }

    private fun sdkmanJavaHomes(userHome: File): List<String> {
        val root = File(userHome, ".sdkman/candidates/java")
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name != "current" }
            .sortedByDescending { it.name }
            .map { it.absolutePath }
    }

    private fun androidStudioJavaHomes(appsRoot: File): List<String> {
        if (!appsRoot.isDirectory) return emptyList()
        return appsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("Android Studio") && it.name.endsWith(".app") }
            .sortedBy { it.name }
            .flatMap { app ->
                listOf(
                    File(app, "Contents/jbr/Contents/Home").absolutePath,
                    File(app, "Contents/jre/Contents/Home").absolutePath,
                )
            }
    }

    private fun homebrewJavaHomes(): List<String> {
        return listOf(
            "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
            "/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home",
            "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
            "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
        )
    }

    private fun captureCommandOutput(command: List<String>): String? {
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output else null
        }.getOrNull()
    }
}
