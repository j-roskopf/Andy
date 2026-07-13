package app.andy.desktop.updates

import app.andy.service.*
import app.andy.updates.AndyBuildInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlin.system.exitProcess

class DesktopAppUpdateService(
    private val scope: CoroutineScope,
    private val currentVersionName: String = AndyBuildInfo.versionName,
    private val githubOwner: String = AndyBuildInfo.githubOwner,
    private val githubRepo: String = AndyBuildInfo.githubRepo,
) : AppUpdateService {

    private val platform: UpdatePlatform = currentDesktopUpdatePlatform()

    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    private val mutablePendingInstallConfirmation = MutableStateFlow<AvailableUpdate?>(null)
    override val pendingInstallConfirmation: StateFlow<AvailableUpdate?> =
        mutablePendingInstallConfirmation.asStateFlow()

    private var pendingInstallConfirmationResponse: CompletableDeferred<Boolean>? = null

    override suspend fun checkForUpdates(onFailure: (Throwable) -> Unit) {
        if (mutableState.value is AppUpdateState.Installing) return
        mutableState.value = AppUpdateState.Checking
        runCatching {
            checkForUpdate()
        }.onSuccess { update ->
            mutableState.value = if (update == null) {
                AppUpdateState.Current
            } else {
                AppUpdateState.Available(update)
            }
            if (update == null) {
                scope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (mutableState.value is AppUpdateState.Current) {
                        mutableState.value = AppUpdateState.Idle
                    }
                }
            }
        }.onFailure { error ->
            onFailure(error)
            mutableState.value = AppUpdateState.Failed(error.message ?: "Couldn't check for updates.")
        }
    }

    override suspend fun installAvailableUpdate(onMessage: (String) -> Unit) {
        val update = when (val state = mutableState.value) {
            is AppUpdateState.Available -> state.update
            is AppUpdateState.Failed -> state.lastKnownUpdate
            is AppUpdateState.Installing -> return
            else -> null
        } ?: return

        val initialMessage = "Downloading Andy ${update.versionName}..."
        mutableState.value = AppUpdateState.Installing(update, initialMessage)
        onMessage(initialMessage)

        scope.launch(Dispatchers.IO) {
            runCatching {
                val asset = update.asset ?: error("No download asset found for current platform.")
                val downloaded = download(asset) { progressMessage, fraction ->
                    mutableState.value = AppUpdateState.Installing(
                        update = update,
                        message = progressMessage,
                        progress = fraction,
                    )
                }

                mutableState.value = AppUpdateState.Installing(
                    update = update,
                    message = "Verifying update...",
                    progress = 1f,
                )
                verifySha256(downloaded, asset.sha256Digest)

                mutableState.value = AppUpdateState.Installing(
                    update = update,
                    message = "Ready to install Andy ${update.versionName}.",
                    progress = 1f,
                )

                val confirmed = requestInstallConfirmation(update)
                if (!confirmed) {
                    onMessage("Install postponed.")
                    mutableState.value = AppUpdateState.Available(update)
                    return@launch
                }

                mutableState.value = AppUpdateState.Installing(
                    update = update,
                    message = "Starting installer...",
                    progress = 1f,
                )

                launchInstallerAndExit(downloaded)
            }.onFailure { error ->
                val message = error.message ?: "Couldn't install the update."
                onMessage(message)
                mutableState.value = AppUpdateState.Failed(message, update)
            }
        }
    }

    override fun respondToInstallConfirmation(install: Boolean) {
        pendingInstallConfirmationResponse?.complete(install)
    }

    private suspend fun requestInstallConfirmation(update: AvailableUpdate): Boolean {
        pendingInstallConfirmationResponse?.complete(false)
        val response = CompletableDeferred<Boolean>()
        pendingInstallConfirmationResponse = response
        mutablePendingInstallConfirmation.value = update
        return try {
            response.await()
        } finally {
            if (pendingInstallConfirmationResponse === response) {
                pendingInstallConfirmationResponse = null
                mutablePendingInstallConfirmation.value = null
            }
        }
    }

    private suspend fun checkForUpdate(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val currentVersion = SemanticVersion.parse(currentVersionName) ?: return@withContext null
        val url = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"

        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Andy/$currentVersionName")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            error("GitHub release check failed: HTTP ${response.statusCode()}")
        }

        val jsonResponse = response.body()
        val parsed = SimpleJsonParser(jsonResponse).parse() as? Map<*, *> ?: error("Invalid JSON response from GitHub")
        val tagName = parsed["tag_name"] as? String ?: ""
        val htmlUrl = parsed["html_url"] as? String ?: ""
        val name = parsed["name"] as? String
        val body = parsed["body"] as? String
        val draft = parsed["draft"] as? Boolean ?: false
        val prerelease = parsed["prerelease"] as? Boolean ?: false

        if (draft || prerelease) return@withContext null

        val latestVersion = SemanticVersion.parse(tagName) ?: return@withContext null
        if (latestVersion <= currentVersion) return@withContext null

        val rawAssets = parsed["assets"] as? List<*> ?: emptyList<Any>()
        val assets = rawAssets.mapNotNull { asset ->
            val assetMap = asset as? Map<*, *> ?: return@mapNotNull null
            GitHubAssetDto(
                name = assetMap["name"] as? String ?: "",
                browserDownloadUrl = assetMap["browser_download_url"] as? String ?: "",
                size = (assetMap["size"] as? Number)?.toLong() ?: 0L,
                digest = assetMap["digest"] as? String,
                state = assetMap["state"] as? String
            )
        }

        val selectedAsset = selectUpdateAsset(platform, assets)
        AvailableUpdate(
            versionName = latestVersion.toString(),
            releaseName = name,
            releaseNotes = body,
            releasePageUrl = htmlUrl,
            asset = selectedAsset
        )
    }

    private fun download(
        asset: ReleaseAsset,
        onProgress: (String, Float?) -> Unit,
    ): File {
        val target = File(
            File(System.getProperty("java.io.tmpdir"), "andy-updates").apply { mkdirs() },
            asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        )
        val request = HttpRequest.newBuilder(URI.create(asset.downloadUrl))
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", "Andy/$currentVersionName")
            .GET()
            .build()

        val response = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
            target.delete()
            error("Update download failed: HTTP ${response.statusCode()}")
        }

        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(asset.sizeBytes)
            .takeIf { it > 0L }
        var downloadedBytes = 0L

        onProgress("Downloading Andy update...", progressFraction(downloadedBytes, totalBytes))

        target.outputStream().use { output ->
            response.body().use { input ->
                val buffer = ByteArray(DownloadBufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    onProgress("Downloading Andy update...", progressFraction(downloadedBytes, totalBytes))
                }
            }
        }
        return target
    }

    private fun verifySha256(file: File, expected: String?) {
        if (expected == null) return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        check(actual == expected) { "Downloaded update failed SHA-256 verification." }
    }

    private fun launchInstallerAndExit(file: File) {
        val relaunchCommand = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.isNotBlank() }

        when (platform) {
            UpdatePlatform.Windows -> {
                val helper = helperFile("andy-update.cmd")
                helper.writeText(windowsInstallerHelperScript(file.absolutePath, relaunchCommand))
                ProcessBuilder("cmd", "/c", "start", "", helper.absolutePath).start()
                exitProcess(0)
            }
            UpdatePlatform.MacOs -> {
                val helper = helperFile("andy-update.sh")
                helper.writeText(macPkgInstallerHelperScript(file.absolutePath))
                helper.setExecutable(true)
                // Keep the helper fully detached from Andy. In particular, it
                // must only hand the archive to macOS and never share the
                // app's streams or run a follow-up relaunch command.
                ProcessBuilder("/bin/sh", helper.absolutePath)
                    .redirectInput(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                exitProcess(0)
            }
            UpdatePlatform.LinuxDeb -> {
                launchLinuxInstaller(file, flatpak = false, relaunchCommand = relaunchCommand)
                exitProcess(0)
            }
            UpdatePlatform.LinuxFlatpak -> {
                launchLinuxInstaller(file, flatpak = true, relaunchCommand = "flatpak run com.joetr.andy")
                exitProcess(0)
            }
            else -> {
                // For other platforms, just open the release webpage
                val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
                if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(URI("https://github.com/$githubOwner/$githubRepo/releases/latest"))
                }
            }
        }
    }

    private fun helperFile(name: String): File =
        File(File(System.getProperty("java.io.tmpdir"), "andy-updates").apply { mkdirs() }, name)

    private fun launchLinuxInstaller(file: File, flatpak: Boolean, relaunchCommand: String?) {
        val insideFlatpak = !System.getenv("FLATPAK_ID").isNullOrBlank()
        val installFile = if (insideFlatpak && flatpak) copyFlatpakBundleToHostDownloads(file) else file.absolutePath
        val helper = helperFile("andy-update.sh")
        helper.writeText(
            linuxInstallerHelperScript(
                filePath = installFile,
                flatpak = flatpak,
                insideFlatpak = insideFlatpak,
                relaunchCommand = relaunchCommand,
            ),
        )
        helper.setExecutable(true)
        ProcessBuilder("/bin/sh", helper.absolutePath).start()
    }

    private fun copyFlatpakBundleToHostDownloads(sandboxFile: File): String {
        val hostFileName = sandboxFile.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val hostPath = resolveHostDownloadsPath(hostFileName)
        val copy = ProcessBuilder(
            "flatpak-spawn",
            "--host",
            "sh",
            "-c",
            "cat > ${hostPath.shellQuote()}",
        )
            .redirectInput(sandboxFile)
            .redirectErrorStream(true)
            .start()
        check(copy.waitFor() == 0) { "Couldn't copy the Flatpak update to the host Downloads folder." }
        return hostPath
    }

    private fun resolveHostDownloadsPath(fileName: String): String {
        val query = ProcessBuilder(
            "flatpak-spawn",
            "--host",
            "sh",
            "-c",
            "printf '%s' \"${'$'}HOME/Downloads/$fileName\"",
        )
            .redirectErrorStream(true)
            .start()
        val hostPath = query.inputStream.bufferedReader().readText().trim()
        check(query.waitFor() == 0 && hostPath.isNotBlank()) {
            "Couldn't resolve the host Downloads path for the Flatpak update."
        }
        return hostPath
    }

    private fun progressFraction(downloadedBytes: Long, totalBytes: Long?): Float? =
        totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }
}

private const val DownloadBufferSize = 256 * 1024

private fun currentDesktopUpdatePlatform(): UpdatePlatform {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "win" in os -> UpdatePlatform.Windows
        "mac" in os -> UpdatePlatform.MacOs
        System.getenv("FLATPAK_ID") == "com.joetr.andy" -> UpdatePlatform.LinuxFlatpak
        "linux" in os -> UpdatePlatform.LinuxDeb
        else -> UpdatePlatform.Other
    }
}

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VersionPattern = Regex("""(?:release/|v)?([0-9]+)\.([0-9]+)\.([0-9]+)(?:[-+].*)?""")

        fun parse(value: String?): SemanticVersion? {
            val match = VersionPattern.matchEntire(value?.trim().orEmpty()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

internal data class GitHubAssetDto(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long = 0L,
    val digest: String? = null,
    val state: String? = null,
) {
    fun toReleaseAsset(): ReleaseAsset =
        ReleaseAsset(
            name = name,
            downloadUrl = browserDownloadUrl,
            sizeBytes = size,
            sha256Digest = digest?.trim()?.lowercase()?.removePrefix("sha256:")
        )
}

internal fun selectUpdateAsset(
    platform: UpdatePlatform,
    assets: List<GitHubAssetDto>,
): ReleaseAsset? {
    val uploadedAssets = assets.filter { asset ->
        asset.browserDownloadUrl.isNotBlank() && asset.state?.equals("uploaded", ignoreCase = true) != false
    }
    fun firstWithExtension(vararg extensions: String): ReleaseAsset? =
        extensions.firstNotNullOfOrNull { extension ->
            uploadedAssets.firstOrNull { asset -> asset.name.endsWith(extension, ignoreCase = true) }
        }?.toReleaseAsset()

    return when (platform) {
        UpdatePlatform.MacOs -> firstWithExtension(".dmg", ".pkg")
        UpdatePlatform.Windows -> firstWithExtension(".msi")
        UpdatePlatform.LinuxFlatpak -> firstWithExtension(".flatpak")
        UpdatePlatform.LinuxDeb -> firstWithExtension(".deb")
        else -> null
    }
}

internal fun windowsInstallerHelperScript(
    msiPath: String,
    relaunchCommand: String?,
): String {
    val relaunch = relaunchCommand?.windowsCmdLine()
    val relaunchLine = if (relaunch == null) "  rem No relaunch command found." else "  start \"\" $relaunch"
    return """
        @echo off
        timeout /t 1 /nobreak >NUL
        msiexec /i ${msiPath.windowsCmdLine()} /passive /norestart
        if %errorlevel%==0 (
        $relaunchLine
        )
    """.trimIndent()
}

internal fun macPkgInstallerHelperScript(pkgPath: String): String {
    return """
        #!/bin/sh
        sleep 1
        # exec makes this a one-way handoff: once macOS opens the update
        # archive, there is no helper shell left that could reopen Andy.
        exec /usr/bin/open -W ${pkgPath.shellQuote()}
    """.trimIndent() + "\n"
}

internal fun linuxInstallerHelperScript(
    filePath: String,
    flatpak: Boolean,
    insideFlatpak: Boolean,
    relaunchCommand: String?,
): String {
    val installCommand = if (flatpak) {
        "flatpak install --user -y ${filePath.shellQuote()} || flatpak install -y ${filePath.shellQuote()}"
    } else {
        "pkexec sh -c ${(("dpkg -i " + filePath.shellQuote()) + " || apt-get install -f -y").shellQuote()}"
    }
    val relaunchBackground = relaunchCommand?.let { command ->
        "(sh -c ${command.shellQuote()} >/dev/null 2>&1 &)"
    } ?: "(sh -c 'andy' >/dev/null 2>&1 &)"
    val hostPrefix = if (insideFlatpak) "flatpak-spawn --host " else ""
    return """
        #!/bin/sh
        sleep 1
        ${hostPrefix}sh -c ${(installCommand + " && " + relaunchBackground).shellQuote()}
    """.trimIndent() + "\n"
}

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun String.windowsCmdLine(): String =
    "\"" + replace("\"", "\\\"") + "\""
