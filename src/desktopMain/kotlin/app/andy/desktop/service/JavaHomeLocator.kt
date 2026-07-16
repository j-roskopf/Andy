package app.andy.desktop.service

import java.io.File

/**
 * Finds a host JDK/JRE for Android cmdline-tools (`avdmanager`, `sdkmanager`).
 *
 * Packaged Andy is often launched from Finder/Dock with a minimal environment and no
 * `JAVA_HOME`. Andy's own jlink runtime also does not ship a `java` launcher, so those
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
        val isMac = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val candidates = buildList {
            env.entries.firstOrNull { it.key.equals("JAVA_HOME", ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            javaHomeProperty?.takeIf { it.isNotBlank() }?.let(::add)
            add(File(userHome, ".sdkman/candidates/java/current").absolutePath)
            addAll(sdkmanJavaHomes(userHome))
            if (isMac) {
                addAll(androidStudioJavaHomes(applications))
                addAll(androidStudioJavaHomes(File(userHome, "Applications")))
                addAll(homebrewJavaHomes())
                addAll(macOsJvmHomes())
                runCommand(listOf("/usr/libexec/java_home"))?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            }
            if (isWindows) {
                addAll(windowsJavaHomes())
            }
            if (!isMac && !isWindows) {
                addAll(linuxJavaHomes())
            }
        }

        return candidates
            .asSequence()
            .map { it.trim().trimEnd('/') }
            .flatMap { path -> normalizeJavaHomeCandidates(path).asSequence() }
            .distinct()
            .firstOrNull(::isUsableJavaHome)
    }

    fun isUsableJavaHome(path: String): Boolean {
        val home = File(path)
        if (!home.isDirectory) return false
        return File(home, "bin/java").canExecute() || File(home, "bin/java.exe").canExecute()
    }

    private fun normalizeJavaHomeCandidates(path: String): List<String> {
        val home = File(path)
        return buildList {
            add(path)
            // macOS JDK bundles and some JREs nest the real home under Contents/Home or jre/.
            add(File(home, "Contents/Home").absolutePath)
            add(File(home, "jre").absolutePath)
            home.parentFile?.absolutePath?.let(::add)
        }
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

    private fun macOsJvmHomes(): List<String> {
        val roots = listOf(
            File("/Library/Java/JavaVirtualMachines"),
            File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
        )
        return roots
            .filter { it.isDirectory }
            .flatMap { root ->
                root.listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .sortedByDescending { it.name }
                    .map { File(it, "Contents/Home").absolutePath }
            }
    }

    private fun windowsJavaHomes(): List<String> {
        return listOfNotNull(
            System.getenv("ProgramFiles")?.let { File(it, "Android/Android Studio/jbr").absolutePath },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/Android Studio/jbr").absolutePath },
        )
    }

    private fun linuxJavaHomes(): List<String> {
        return listOf(
            "/usr/lib/jvm/default-java",
            "/usr/lib/jvm/java-21-openjdk",
            "/usr/lib/jvm/java-17-openjdk",
            "/usr/lib/jvm/java-21-openjdk-amd64",
            "/usr/lib/jvm/java-17-openjdk-amd64",
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
