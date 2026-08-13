package app.andy.terminal.rust

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Locates and loads the Phase-0 `andy_terminal_engine` cdylib.
 *
 * Packaging mirrors the existing andy-mirror / andy-notifications JNI pattern:
 * platform slice under resources → copy to `~/.andy/...` → `System.load`.
 *
 * Not wired into the production BossTerm path.
 */
internal object RustTerminalNative {
    private val loadResult: Result<File> by lazy(::loadLibrary)

    fun ensureLoaded(): File = loadResult.getOrThrow()

    fun isAvailable(): Boolean = loadResult.isSuccess

    fun libraryFile(): File? = loadResult.getOrNull()

    private fun loadLibrary(): Result<File> = runCatching {
        val resourcePath = resourcePath()
            ?: error("No andy-terminal-engine dylib is packaged for this platform")
        val target = File(System.getProperty("user.home"), ".andy/terminal-engine/$resourcePath")
        target.parentFile.mkdirs()

        val override = System.getProperty("andy.terminal.engine.dylib")
        if (!override.isNullOrBlank()) {
            val file = File(override)
            check(file.isFile) { "andy.terminal.engine.dylib does not exist: $override" }
            System.load(file.absolutePath)
            // UniFFI's generated Kotlin looks up the same library via JNA.
            System.setProperty(
                "uniffi.component.andy_terminal_engine.libraryOverride",
                file.absolutePath,
            )
            return@runCatching file
        }

        javaClass.classLoader.getResourceAsStream(resourcePath)?.use {
            Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Missing packaged andy-terminal-engine dylib: $resourcePath")
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
        if (!os.contains("mac") && !os.contains("darwin")) return null
        return when (osArch.lowercase()) {
            "aarch64", "arm64" -> "andy-terminal-engine/macos-arm64/libandy_terminal_engine.dylib"
            "x86_64", "amd64" -> "andy-terminal-engine/macos-x86_64/libandy_terminal_engine.dylib"
            else -> null
        }
    }
}
