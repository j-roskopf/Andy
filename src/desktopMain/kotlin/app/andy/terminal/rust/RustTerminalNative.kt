package app.andy.terminal.rust

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Locates and loads the `andy_terminal_engine` cdylib for the host OS/arch.
 *
 * Packaging mirrors the existing andy-mirror / andy-notifications JNI pattern:
 * platform slice under resources → copy to `~/.andy/...` → `System.load`.
 */
internal object RustTerminalNative {
    private val loadResult: Result<File> by lazy(::loadLibrary)

    fun ensureLoaded(): File = loadResult.getOrThrow()

    fun isAvailable(): Boolean = loadResult.isSuccess

    fun libraryFile(): File? = loadResult.getOrNull()

    private fun loadLibrary(): Result<File> = runCatching {
        val resourcePath = resourcePath()
            ?: error("No andy-terminal-engine native library is packaged for this platform")
        val target = File(System.getProperty("user.home"), ".andy/terminal-engine/$resourcePath")
        target.parentFile.mkdirs()

        val override = System.getProperty("andy.terminal.engine.dylib")
            ?: System.getProperty("andy.terminal.engine.native")
        if (!override.isNullOrBlank()) {
            val file = File(override)
            check(file.isFile) { "andy.terminal.engine.native does not exist: $override" }
            System.load(file.absolutePath)
            System.setProperty(
                "uniffi.component.andy_terminal_engine.libraryOverride",
                file.absolutePath,
            )
            return@runCatching file
        }

        javaClass.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
            try {
                Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Windows locks a loaded DLL; a second concurrent Andy process cannot replace it.
                // Fall back to the already-extracted library when present.
                check(target.isFile) {
                    "Failed to extract andy-terminal-engine library and no existing copy at ${target.absolutePath}"
                }
            }
        } ?: error("Missing packaged andy-terminal-engine library: $resourcePath")
        System.load(target.absolutePath)
        System.setProperty(
            "uniffi.component.andy_terminal_engine.libraryOverride",
            target.absolutePath,
        )
        target
    }

    internal fun resourcePath(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): String? {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val slice = when {
            os.contains("mac") || os.contains("darwin") -> when (arch) {
                "aarch64", "arm64" -> "macos-arm64"
                "x86_64", "amd64" -> "macos-x86_64"
                else -> return null
            }
            os.contains("linux") -> when (arch) {
                "aarch64", "arm64" -> "linux-arm64"
                "x86_64", "amd64" -> "linux-x86_64"
                else -> return null
            }
            os.contains("windows") -> when (arch) {
                "x86_64", "amd64" -> "windows-x86_64"
                "aarch64", "arm64" -> "windows-arm64"
                else -> return null
            }
            else -> return null
        }
        val fileName = when {
            slice.startsWith("windows") -> "andy_terminal_engine.dll"
            slice.startsWith("linux") -> "libandy_terminal_engine.so"
            else -> "libandy_terminal_engine.dylib"
        }
        return "andy-terminal-engine/$slice/$fileName"
    }
}
