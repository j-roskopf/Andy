package app.andy.desktop.service.voice

import app.andy.service.VoiceSetupService
import app.andy.service.VoiceSetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

fun interface VoiceHttpDownloader {
    fun download(
        url: String,
        dest: File,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    )
}

/** Installs downloaded binary archives into bin/lib/libexec. Overridable in tests. */
fun interface VoiceBinaryInstaller {
    fun install(platform: VoiceArtifacts.Platform, primaryArchive: File, extraArchives: List<File>)
}

class DesktopVoiceSetupService(
    private val home: File = File(System.getProperty("user.home")),
    private val platform: VoiceArtifacts.Platform? = VoiceArtifacts.detectPlatform(),
    private val downloader: VoiceHttpDownloader = DefaultVoiceHttpDownloader,
    private val binaryInstaller: VoiceBinaryInstaller? = null,
    private val binaryPackageFor: (VoiceArtifacts.Platform) -> VoiceArtifacts.BinaryPackage =
        VoiceArtifacts::binaryPackage,
    private val modelUrl: String = VoiceArtifacts.MODEL_URL,
    private val modelSha256: String = VoiceArtifacts.MODEL_SHA256,
    private val modelBytes: Long = VoiceArtifacts.MODEL_BYTES,
) : VoiceSetupService {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val voiceRoot = File(home, ".andy/voice")
    private val binDir = File(voiceRoot, "bin")
    private val libDir = File(voiceRoot, "lib")
    private val libexecDir = File(voiceRoot, "libexec")
    private val modelsDir = File(voiceRoot, "models")
    private val runtimeDir = File(voiceRoot, "runtime")
    private val stateFile = File(voiceRoot, "state.json")
    private val enableMutex = Mutex()
    /** Set by [disable] to abort an in-flight [enable] before it can persist `enabled=true`. */
    private val enableAborted = AtomicBoolean(false)

    private val _state = MutableStateFlow(readInitialState())
    override val state: StateFlow<VoiceSetupState> = _state.asStateFlow()

    fun binaryFile(): File = File(binDir, if (platform == VoiceArtifacts.Platform.WindowsX64) "whisper-cli.exe" else "whisper-cli")
    fun modelFile(): File = File(modelsDir, VoiceArtifacts.MODEL_NAME)
    fun libDirectory(): File = libDir
    fun libexecDirectory(): File = libexecDir
    /** Preferred ggml backend shared library for `GGML_BACKEND_PATH`, if installed. */
    fun preferredBackendFile(): File? = preferredGgmlBackend(libexecDir)

    private fun readInitialState(): VoiceSetupState {
        val persisted = readStateJson()
        return when {
            persisted?.enabled != true -> VoiceSetupState.NotEnabled
            binaryReady() && modelReady() -> VoiceSetupState.Ready
            else -> VoiceSetupState.NotEnabled
        }
    }

    override suspend fun enable() {
        enableMutex.withLock {
            enableAborted.set(false)
            withContext(Dispatchers.IO) { doEnable() }
        }
    }

    override fun disable() {
        enableAborted.set(true)
        val current = readStateJson() ?: VoiceStateJson()
        writeStateJson(current.copy(enabled = false))
        _state.value = VoiceSetupState.NotEnabled
    }

    private fun aborted(): Boolean = enableAborted.get()

    private fun doEnable() {
        if (aborted()) return
        val plat = platform
            ?: run {
                if (!aborted()) {
                    _state.value = VoiceSetupState.Failed("platform", "Voice dictation is not supported on this OS/arch")
                }
                return
            }
        voiceRoot.mkdirs()
        binDir.mkdirs()
        libDir.mkdirs()
        libexecDir.mkdirs()
        modelsDir.mkdirs()

        if (!binaryReady()) {
            try {
                if (aborted()) return
                installBinary(plat)
                if (aborted()) return
                // Persist immediately so a later model failure retries only the model step.
                // Keep enabled=false here — only the final success path flips it on.
                val persisted = readStateJson() ?: VoiceStateJson()
                writeStateJson(
                    persisted.copy(
                        binaryVersion = VoiceArtifacts.BINARY_VERSION,
                        enabled = false,
                    ),
                )
            } catch (t: Throwable) {
                if (t is VoiceEnableAborted || aborted()) return
                _state.value = VoiceSetupState.Failed("binary", t.message ?: t::class.simpleName.orEmpty())
                return
            }
        }

        if (aborted()) return

        if (!modelReady()) {
            try {
                if (aborted()) return
                installModel()
            } catch (t: Throwable) {
                if (t is VoiceEnableAborted || aborted()) return
                _state.value = VoiceSetupState.Failed("model", t.message ?: t::class.simpleName.orEmpty())
                return
            }
        }

        if (aborted()) return

        // Homebrew-relocated macOS runtimes are easy to mark Ready while unloadable; smoke
        // before persisting enabled=true. Linux/Windows official builds are left as-is.
        if (plat == VoiceArtifacts.Platform.MacOsArm64 || plat == VoiceArtifacts.Platform.MacOsX64) {
            try {
                smokeCheckBinary(plat)
            } catch (t: Throwable) {
                if (t is VoiceEnableAborted || aborted()) return
                _state.value = VoiceSetupState.Failed("binary", t.message ?: t::class.simpleName.orEmpty())
                return
            }
        }

        if (aborted()) return

        writeStateJson(
            VoiceStateJson(
                binaryVersion = VoiceArtifacts.BINARY_VERSION,
                model = VoiceArtifacts.MODEL_NAME,
                enabled = true,
            ),
        )
        // disable() may have won the race after the write above — honor the user's choice.
        if (aborted()) {
            writeStateJson(
                (readStateJson() ?: VoiceStateJson()).copy(enabled = false),
            )
            _state.value = VoiceSetupState.NotEnabled
            return
        }
        _state.value = VoiceSetupState.Ready
    }

    private fun binaryReady(): Boolean {
        val bin = binaryFile()
        if (!bin.isFile) return false
        val persisted = readStateJson()
        if (persisted?.binaryVersion != VoiceArtifacts.BINARY_VERSION) return false
        if (platform != VoiceArtifacts.Platform.WindowsX64 && !bin.canExecute()) return false
        if (platform == VoiceArtifacts.Platform.MacOsArm64 || platform == VoiceArtifacts.Platform.MacOsX64) {
            if (!File(libDir, "libomp.dylib").isFile) return false
            if (preferredGgmlBackend(libexecDir) == null) return false
        }
        return true
    }

    private fun modelReady(): Boolean {
        val model = modelFile()
        if (!model.isFile || model.length() != modelBytes) return false
        return sha256(model).equals(modelSha256, ignoreCase = true)
    }

    private fun installBinary(plat: VoiceArtifacts.Platform) {
        val pkg = binaryPackageFor(plat)
        val tmp = Files.createTempDirectory("andy-voice-bin").toFile()
        try {
            val primaryArchive = File(tmp, "primary.bin")
            downloadVerified(pkg.primary, primaryArchive)
            val extraArchives = buildList {
                pkg.secondary?.let { secondary ->
                    add(File(tmp, "secondary.bin").also { downloadVerified(secondary, it) })
                }
                pkg.tertiary?.let { tertiary ->
                    add(File(tmp, "tertiary.bin").also { downloadVerified(tertiary, it) })
                }
            }
            val installer = binaryInstaller ?: VoiceBinaryInstaller { platform, primary, extras ->
                when (platform) {
                    VoiceArtifacts.Platform.LinuxX64 -> installLinux(primary)
                    VoiceArtifacts.Platform.WindowsX64 -> installWindows(primary)
                    VoiceArtifacts.Platform.MacOsArm64,
                    VoiceArtifacts.Platform.MacOsX64,
                    -> installMacOs(
                        whisperBottle = primary,
                        ggmlBottle = extras.getOrNull(0) ?: error("macOS package missing ggml bottle"),
                        libompBottle = extras.getOrNull(1) ?: error("macOS package missing libomp bottle"),
                    )
                }
            }
            installer.install(plat, primaryArchive, extraArchives)
            val bin = binaryFile()
            if (!bin.isFile) error("whisper-cli missing after install")
            if (plat != VoiceArtifacts.Platform.WindowsX64) {
                bin.setExecutable(true)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun installModel() {
        ensureDiskSpace(modelBytes)
        val dest = modelFile()
        val partial = File(modelsDir, "${VoiceArtifacts.MODEL_NAME}.partial")
        partial.delete()
        downloadVerified(
            VoiceArtifacts.Download(
                url = modelUrl,
                sha256 = modelSha256,
                expectedBytes = modelBytes,
                label = "model ${VoiceArtifacts.MODEL_NAME}",
            ),
            partial,
        )
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }

    private fun downloadVerified(download: VoiceArtifacts.Download, dest: File) {
        if (aborted()) throw VoiceEnableAborted()
        dest.parentFile?.mkdirs()
        _state.value = VoiceSetupState.Downloading(download.label, 0f)
        download.expectedBytes?.let { ensureDiskSpace(it) }
        downloader.download(download.url, dest, download.headers) { progress ->
            if (aborted()) throw VoiceEnableAborted()
            _state.value = VoiceSetupState.Downloading(download.label, progress.coerceIn(0f, 1f))
        }
        if (aborted()) throw VoiceEnableAborted()
        val actual = sha256(dest)
        if (!actual.equals(download.sha256, ignoreCase = true)) {
            dest.delete()
            error("Checksum mismatch for ${download.label}: expected ${download.sha256}, got $actual")
        }
    }

    private fun installLinux(archive: File) {
        runtimeDir.deleteRecursively()
        runtimeDir.mkdirs()
        extractTarGz(archive, runtimeDir)
        val extractedBin = runtimeDir.walkTopDown().firstOrNull { it.name == "whisper-cli" && it.isFile }
            ?: error("whisper-cli not found in linux archive")
        val runtimeRoot = extractedBin.parentFile
        copyTreeLibs(runtimeRoot, libDir)
        extractedBin.copyTo(binaryFile(), overwrite = true)
    }

    private fun installWindows(archive: File) {
        runtimeDir.deleteRecursively()
        runtimeDir.mkdirs()
        extractZip(archive, runtimeDir)
        val extractedBin = runtimeDir.walkTopDown().firstOrNull {
            it.name.equals("whisper-cli.exe", ignoreCase = true) && it.isFile
        } ?: error("whisper-cli.exe not found in windows archive")
        val runtimeRoot = extractedBin.parentFile
        copyTreeLibs(runtimeRoot, libDir)
        extractedBin.copyTo(binaryFile(), overwrite = true)
    }

    private fun installMacOs(whisperBottle: File, ggmlBottle: File, libompBottle: File) {
        runtimeDir.deleteRecursively()
        runtimeDir.mkdirs()
        libDir.deleteRecursively()
        libDir.mkdirs()
        libexecDir.deleteRecursively()
        libexecDir.mkdirs()
        val whisperExtract = File(runtimeDir, "whisper").also { it.mkdirs() }
        val ggmlExtract = File(runtimeDir, "ggml").also { it.mkdirs() }
        val libompExtract = File(runtimeDir, "libomp").also { it.mkdirs() }
        extractTarGz(whisperBottle, whisperExtract)
        extractTarGz(ggmlBottle, ggmlExtract)
        extractTarGz(libompBottle, libompExtract)
        val extractedBin = whisperExtract.walkTopDown().firstOrNull { it.name == "whisper-cli" && it.isFile }
            ?: error("whisper-cli not found in macOS bottle")
        whisperExtract.walkTopDown().filter { it.isFile && it.extension == "dylib" }.forEach { dylib ->
            dylib.copyTo(File(libDir, dylib.name), overwrite = true)
        }
        ggmlExtract.walkTopDown().filter { it.isFile && it.extension == "dylib" }.forEach { dylib ->
            dylib.copyTo(File(libDir, dylib.name), overwrite = true)
        }
        val libomp = libompExtract.walkTopDown().firstOrNull { it.name == "libomp.dylib" && it.isFile }
            ?: error("libomp.dylib not found in libomp bottle")
        libomp.copyTo(File(libDir, "libomp.dylib"), overwrite = true)
        // CPU/Metal backends live under ggml/.../libexec and are loaded via GGML_BACKEND_PATH.
        val backends = ggmlExtract.walkTopDown()
            .filter { it.isFile && it.parentFile?.name == "libexec" }
            .toList()
        if (backends.isEmpty()) error("ggml libexec backends missing from bottle")
        backends.forEach { backend ->
            backend.copyTo(File(libexecDir, backend.name), overwrite = true)
        }
        extractedBin.copyTo(binaryFile(), overwrite = true)
        binaryFile().setExecutable(true)
        relocateMacBinaries(binaryFile(), libDir, libexecDir)
        adHocCodesign(listOf(binaryFile()) + libDir.listFiles().orEmpty().toList() + libexecDir.listFiles().orEmpty().toList())
    }

    private fun relocateMacBinaries(binary: File, libs: File, backends: File) {
        val targets = buildList {
            add(binary)
            libs.listFiles()?.filter { it.isFile }?.let { addAll(it) }
            backends.listFiles()?.filter { it.isFile }?.let { addAll(it) }
        }
        for (file in targets) {
            if (file.extension == "dylib") {
                runInstallNameTool("-id", File(libs, file.name).absolutePath, file.absolutePath)
            }
            for (dep in otoolDeps(file)) {
                val base = File(dep).name
                val replacement = File(libs, base)
                val needsRewrite = dep.contains("@@HOMEBREW_PREFIX@@") ||
                    dep.startsWith("@rpath/") ||
                    (replacement.isFile && File(dep).absoluteFile.normalize() != replacement.absoluteFile.normalize())
                if (needsRewrite && replacement.isFile) {
                    runInstallNameTool("-change", dep, replacement.absolutePath, file.absolutePath)
                } else if (
                    dep.contains("@@HOMEBREW_PREFIX@@") ||
                    dep.contains("/opt/libomp/") ||
                    (dep.startsWith("@rpath/") && !replacement.isFile)
                ) {
                    error("Unresolved macOS dependency after relocate: $dep (from ${file.name})")
                }
            }
            runInstallNameToolAllowDuplicateRpath("-add_rpath", libs.absolutePath, file.absolutePath)
        }
        // Fail closed: every local binary/dylib/backend must resolve without Homebrew placeholders.
        for (file in targets) {
            val unresolved = otoolDeps(file).filter {
                it.contains("@@HOMEBREW_PREFIX@@") ||
                    it.contains("/opt/libomp/") ||
                    (it.startsWith("@rpath/") && !File(libs, File(it).name).isFile)
            }
            if (unresolved.isNotEmpty()) {
                error("macOS voice runtime still references Homebrew paths in ${file.name}: $unresolved")
            }
        }
    }

    private fun runInstallNameTool(vararg args: String) {
        val process = ProcessBuilder("install_name_tool", *args)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            error("install_name_tool ${args.joinToString(" ")} failed ($code): $out")
        }
    }

    private fun runInstallNameToolAllowDuplicateRpath(vararg args: String) {
        val process = ProcessBuilder("install_name_tool", *args)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0 && !out.contains("would duplicate", ignoreCase = true)) {
            error("install_name_tool ${args.joinToString(" ")} failed ($code): $out")
        }
    }

    private fun adHocCodesign(files: List<File>) {
        for (file in files.filter { it.isFile }) {
            val process = ProcessBuilder(
                "codesign", "-s", "-", "-f", "--timestamp=none", file.absolutePath,
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                error("codesign failed for ${file.name} ($code): $out")
            }
        }
    }

    private fun smokeCheckBinary(plat: VoiceArtifacts.Platform) {
        if (aborted()) throw VoiceEnableAborted()
        val bin = binaryFile()
        if (!bin.isFile) error("whisper-cli missing for smoke check")
        val command = listOf(bin.absolutePath, "--help")
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        val env = pb.environment()
        if (libDir.isDirectory) {
            env["DYLD_LIBRARY_PATH"] = prependPathEnv(env["DYLD_LIBRARY_PATH"], libDir.absolutePath)
            env["LD_LIBRARY_PATH"] = prependPathEnv(env["LD_LIBRARY_PATH"], libDir.absolutePath)
        }
        preferredGgmlBackend(libexecDir)?.let { backend ->
            env["GGML_BACKEND_PATH"] = backend.absolutePath
        }
        val process = pb.start()
        val out = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("whisper-cli smoke check timed out")
        }
        val code = process.exitValue()
        if (code != 0) {
            error("whisper-cli smoke check failed ($code): ${out.take(400)}")
        }
        if (!out.contains("usage", ignoreCase = true) && !out.contains("--help")) {
            // macOS Homebrew builds print usage; accept version-only successes too.
            if (plat == VoiceArtifacts.Platform.MacOsArm64 || plat == VoiceArtifacts.Platform.MacOsX64) {
                error("whisper-cli smoke check produced no usage text: ${out.take(400)}")
            }
        }
    }

    private fun prependPathEnv(existing: String?, first: String): String =
        if (existing.isNullOrBlank()) first else "$first${File.pathSeparator}$existing"

    private fun otoolDeps(file: File): List<String> {
        val process = ProcessBuilder("otool", "-L", file.absolutePath)
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out.lineSequence()
            .drop(1)
            .map { it.trim().substringBefore(" (") }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun copyTreeLibs(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { child ->
            if (child.isFile && (
                    child.extension in setOf("so", "dylib", "dll") ||
                        child.name.startsWith("lib")
                    )
            ) {
                child.copyTo(File(to, child.name), overwrite = true)
            }
        }
    }

    private fun ensureDiskSpace(neededBytes: Long) {
        val required = (neededBytes * 1.2).toLong()
        val usable = voiceRoot.usableSpace.takeIf { voiceRoot.exists() }
            ?: home.usableSpace
        if (usable in 1 until required) {
            error(
                "Not enough free disk space for voice download " +
                    "(need ~${required / (1024 * 1024)} MB, have ${usable / (1024 * 1024)} MB)",
            )
        }
    }

    private fun readStateJson(): VoiceStateJson? {
        val text = stateFile.takeIf { it.isFile }?.readText() ?: return null
        return runCatching { json.decodeFromString<VoiceStateJson>(text) }.getOrNull()
    }

    private fun writeStateJson(state: VoiceStateJson) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(state))
    }

}

/** Thrown when [DesktopVoiceSetupService.disable] aborts an in-flight enable/download. */
private class VoiceEnableAborted : RuntimeException("voice enable aborted by disable")

@Serializable
internal data class VoiceStateJson(
    val binaryVersion: String = "",
    val model: String = VoiceArtifacts.MODEL_NAME,
    val enabled: Boolean = false,
)

internal object DefaultVoiceHttpDownloader : VoiceHttpDownloader {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override fun download(
        url: String,
        dest: File,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ) {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "Andy-VoiceSetup/1.0")
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            dest.delete()
            error("Download failed: HTTP ${response.statusCode()} for $url")
        }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var downloaded = 0L
        dest.outputStream().use { output ->
            response.body().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                    else onProgress(0f)
                }
            }
        }
        onProgress(1f)
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun extractTarGz(archive: File, dest: File) {
    dest.mkdirs()
    val process = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", dest.absolutePath)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) error("tar extract failed ($code): $out")
}

internal fun extractZip(archive: File, dest: File) {
    dest.mkdirs()
    val destRoot = dest.canonicalFile
    ZipInputStream(FileInputStream(archive)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            val outFile = File(dest, entry.name).canonicalFile
            val underDest = outFile == destRoot ||
                outFile.path.startsWith(destRoot.path + File.separator)
            if (!underDest) {
                error("Zip entry escapes destination: ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output -> zis.copyTo(output) }
            }
            zis.closeEntry()
        }
    }
}

internal fun preferredGgmlBackend(libexecDir: File): File? {
    if (!libexecDir.isDirectory) return null
    val files = libexecDir.listFiles()?.filter { it.isFile }.orEmpty()
    if (files.isEmpty()) return null
    val preferredNames = listOf(
        "libggml-cpu-apple_m4.so",
        "libggml-cpu-apple_m2_m3.so",
        "libggml-cpu-apple_m1.so",
        "libggml-cpu.so",
        "libggml-blas.so",
        "libggml-metal.so",
    )
    for (name in preferredNames) {
        files.firstOrNull { it.name == name }?.let { return it }
    }
    return files.firstOrNull { it.name.startsWith("libggml-") }
}
