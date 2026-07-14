package app.andy.desktop.service.mirror

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Resolves only Andy-packaged native mirrors (plus an explicit development override). */
internal object AndyMirrorBinaryLocator {
    fun find(): File? {
        System.getProperty("andy.mirror.path")?.takeIf(String::isNotBlank)?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { return it }
        System.getenv("ANDY_MIRROR_PATH")?.takeIf(String::isNotBlank)?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { return it }
        return bundledBinary()
    }

    internal fun resourcePath(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): String? {
        val tag = platformTag(osName, osArch) ?: return null
        return "andy-mirror/$tag/${if (tag.startsWith("windows")) "andy-mirror.exe" else "andy-mirror"}"
    }

    internal fun platformTag(osName: String, osArch: String): String? {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val arm64 = arch == "aarch64" || arch == "arm64"
        return when {
            os.contains("mac") || os.contains("darwin") -> if (arm64) "macos-arm64" else "macos-x86_64"
            os.contains("windows") && !arm64 -> "windows-x86_64"
            os.contains("linux") -> if (arm64) "linux-arm64" else "linux-x86_64"
            else -> null
        }
    }

    private fun bundledBinary(): File? {
        val resourcePath = resourcePath() ?: return null
        val input = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return null
        val target = File(System.getProperty("user.home"), ".andy/mirror/$resourcePath")
        target.parentFile.mkdirs()
        return runCatching {
            input.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            target.setExecutable(true, false)
            target.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }
}
