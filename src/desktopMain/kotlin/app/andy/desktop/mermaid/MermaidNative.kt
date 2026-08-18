package app.andy.desktop.mermaid

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Locates and loads the `andy_mermaid` cdylib for the host OS/arch.
 *
 * Packaging mirrors [app.andy.terminal.rust.RustTerminalNative]: platform slice under
 * resources → copy to `~/.andy/mermaid/…` → [System.load].
 */
internal object MermaidNative {
    private val loadResult: Result<File> by lazy(::loadLibrary)

    fun isAvailable(): Boolean = loadResult.isSuccess

    fun ensureLoaded(): File = loadResult.getOrThrow()

    private fun loadLibrary(): Result<File> = runCatching {
        val resourcePath = resourcePath()
            ?: error("No andy-mermaid native library is packaged for this platform")
        val target = File(System.getProperty("user.home"), ".andy/mermaid/$resourcePath")
        target.parentFile.mkdirs()

        val override = System.getProperty("andy.mermaid.native")
        if (!override.isNullOrBlank()) {
            val file = File(override)
            check(file.isFile) { "andy.mermaid.native does not exist: $override" }
            System.load(file.absolutePath)
            return@runCatching file
        }

        javaClass.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
            try {
                Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                check(target.isFile) {
                    "Failed to extract andy-mermaid library and no existing copy at ${target.absolutePath}"
                }
            }
        } ?: error("Missing packaged andy-mermaid library: $resourcePath")
        System.load(target.absolutePath)
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
            slice.startsWith("windows") -> "andy_mermaid.dll"
            slice.startsWith("linux") -> "libandy_mermaid.so"
            else -> "libandy_mermaid.dylib"
        }
        return "andy-mermaid/$slice/$fileName"
    }
}

/** JNI boundary for mermaid source → PNG. */
internal object MermaidJni {
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 16
    }

    fun renderPng(source: String, dark: Boolean): Result<ByteArray> {
        if (!MermaidNative.isAvailable()) {
            return Result.failure(IllegalStateException("andy-mermaid native library is unavailable"))
        }
        MermaidNative.ensureLoaded()
        val key = "${if (dark) "d" else "l"}\n$source"
        synchronized(lock) {
            cache[key]?.let { return Result.success(it) }
        }
        return runCatching {
            synchronized(lock) {
                cache[key] ?: nativeRenderPng(source, dark).also { cache[key] = it }
            }
        }
    }

    @JvmStatic
    external fun nativeRenderPng(source: String, dark: Boolean): ByteArray
}
