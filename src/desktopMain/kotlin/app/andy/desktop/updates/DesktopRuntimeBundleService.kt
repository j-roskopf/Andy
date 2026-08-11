package app.andy.desktop.updates

import app.andy.desktop.service.AndydProcess
import app.andy.desktop.service.agents.AndyPiExtensionInstaller
import app.andy.desktop.service.agents.AndyStatusHookInstaller
import app.andy.desktop.service.agents.OrchestrationSkillInstaller
import app.andy.service.RuntimeBundleService
import app.andy.service.RuntimeBundleSnapshot
import app.andy.service.RuntimeBundleState
import app.andy.service.RuntimeComponentStatus
import app.andy.updates.AndyBuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Probes `~/.andy` and installs CLI / andyd / tmux / status hook from GitHub latest,
 * matching [scripts/install-andy.sh]. Local extras (Pi extension, orchestration skills)
 * are refreshed from the running app build.
 */
class DesktopRuntimeBundleService(
    private val andyHome: File = File(System.getProperty("user.home"), ".andy"),
    private val githubOwner: String = AndyBuildInfo.githubOwner,
    private val githubRepo: String = AndyBuildInfo.githubRepo,
    private val userAgent: String = "Andy/${AndyBuildInfo.versionName}",
    private val platformTarget: String? = detectRuntimeInstallTarget(),
) : RuntimeBundleService {

    private val mutableState = MutableStateFlow<RuntimeBundleState>(RuntimeBundleState.Idle)
    override val state: StateFlow<RuntimeBundleState> = mutableState.asStateFlow()

    private val binDir get() = File(andyHome, "bin")
    private val runtimeDir get() = File(andyHome, "andyd")
    private val versionFile get() = File(andyHome, INSTALLED_RELEASE_FILE)

    override suspend fun refresh(checkLatest: Boolean) {
        if (mutableState.value is RuntimeBundleState.Installing) return
        mutableState.value = RuntimeBundleState.Checking
        runCatching {
            probe(checkLatest = checkLatest)
        }.onSuccess { snapshot ->
            mutableState.value = RuntimeBundleState.Ready(snapshot)
        }.onFailure { error ->
            mutableState.value = RuntimeBundleState.Failed(
                message = error.message ?: "Couldn't check runtime installs.",
                snapshot = runCatching { probe(checkLatest = false) }.getOrNull(),
            )
        }
    }

    override suspend fun installOrUpdateFromLatest() {
        if (mutableState.value is RuntimeBundleState.Installing) return
        val prior = (mutableState.value as? RuntimeBundleState.Ready)?.snapshot
            ?: (mutableState.value as? RuntimeBundleState.Failed)?.snapshot
            ?: runCatching { probe(checkLatest = false) }.getOrNull()

        if (platformTarget == null) {
            mutableState.value = RuntimeBundleState.Failed(
                message = "The andy CLI and andyd are only supported on macOS and Linux.",
                snapshot = prior,
            )
            return
        }

        mutableState.value = RuntimeBundleState.Installing(
            snapshot = prior,
            message = "Checking latest GitHub release…",
        )

        withContext(Dispatchers.IO) {
            runCatching {
                val release = fetchLatestRelease()
                val version = normalizeReleaseVersion(release.tagName)
                    ?: error("Could not parse release version from ${release.tagName}")
                val target = platformTarget

                fun progress(message: String, fraction: Float?) {
                    mutableState.value = RuntimeBundleState.Installing(prior, message, fraction)
                }

                val cliAsset = findAsset(release.assets, "^andy-.+-${Regex.escape(target)}$".toRegex())
                    ?: error("No andy-$version-$target asset in the latest release.")
                val andydAsset = findAsset(release.assets, "^andyd-.+-${Regex.escape(target)}\\.jar$".toRegex())
                val tmuxAsset = findAsset(release.assets, "^tmux-.+-${Regex.escape(target)}$".toRegex())
                val hookAsset = findAsset(release.assets, "^andy-status-hook\\.sh$".toRegex())

                binDir.mkdirs()
                runtimeDir.mkdirs()
                val tmp = File(System.getProperty("java.io.tmpdir"), "andy-runtime-updates").apply { mkdirs() }

                progress("Downloading andy CLI…", 0.05f)
                val cliTmp = File(tmp, "andy")
                downloadAsset(cliAsset, cliTmp) { frac ->
                    progress("Downloading andy CLI…", 0.05f + 0.35f * (frac ?: 0f))
                }
                installExecutable(cliTmp, File(binDir, "andy"))

                if (andydAsset != null) {
                    progress("Downloading andyd runtime…", 0.45f)
                    val jarTmp = File(tmp, "andyd.jar")
                    downloadAsset(andydAsset, jarTmp) { frac ->
                        progress("Downloading andyd runtime…", 0.45f + 0.25f * (frac ?: 0f))
                    }
                    val jarDest = File(runtimeDir, "andyd.jar")
                    jarTmp.copyTo(jarDest, overwrite = true)
                    jarTmp.delete()
                }

                progress("Installing andyd launcher…", 0.72f)
                writeAndydLauncher(File(binDir, "andyd"))

                if (tmuxAsset != null) {
                    progress("Downloading bundled tmux…", 0.75f)
                    val tmuxTmp = File(tmp, "tmux")
                    downloadAsset(tmuxAsset, tmuxTmp) { frac ->
                        progress("Downloading bundled tmux…", 0.75f + 0.12f * (frac ?: 0f))
                    }
                    installExecutable(tmuxTmp, File(binDir, "tmux"))
                } else {
                    linkSystemTmuxIfNeeded()
                }

                // andyHome is ~/.andy — parent is the user home when using the default path.
                val home = if (andyHome.name == ".andy") {
                    andyHome.parentFile ?: File(System.getProperty("user.home"))
                } else {
                    File(System.getProperty("user.home"))
                }

                progress("Installing status hook…", 0.90f)
                if (hookAsset != null) {
                    val hookTmp = File(tmp, "andy-status-hook.sh")
                    downloadAsset(hookAsset, hookTmp) { }
                    installExecutable(hookTmp, File(binDir, AndyStatusHookInstaller.SCRIPT_NAME))
                }

                progress("Refreshing local extras…", 0.94f)
                AndyStatusHookInstaller.ensureInstalled(home)
                AndyPiExtensionInstaller.ensureInstalled(home)
                OrchestrationSkillInstaller.ensureInstalled(home)
                writeOpenCodeCanonical(home)

                writeInstalledRelease(version, release.htmlUrl)

                val wasRunning = AndydProcess.isExternalDaemonLive()
                if (wasRunning && andydAsset != null) {
                    progress("Restarting andyd…", 0.97f)
                    restartAndyd()
                }

                progress("Done.", 1f)
                val snapshot = probe(checkLatest = false).copy(
                    latestReleaseVersion = version,
                    latestReleasePageUrl = release.htmlUrl,
                    updateAvailable = false,
                )
                mutableState.value = RuntimeBundleState.Ready(snapshot)
            }.onFailure { error ->
                mutableState.value = RuntimeBundleState.Failed(
                    message = error.message ?: "Couldn't install the runtime bundle.",
                    snapshot = prior,
                )
            }
        }
    }

    private suspend fun probe(checkLatest: Boolean): RuntimeBundleSnapshot = withContext(Dispatchers.IO) {
        val installedVersion = readInstalledReleaseVersion()
        val home = andyHome.parentFile ?: File(System.getProperty("user.home"))
        val cli = File(binDir, "andy")
        val andyd = File(binDir, "andyd")
        val jar = File(runtimeDir, "andyd.jar")
        val tmux = File(binDir, "tmux")
        val hook = AndyStatusHookInstaller.scriptFile(home)
        val pi = AndyPiExtensionInstaller.extensionPath(home)
        val opencode = File(home, ".andy/opencode/andy-status.js")

        val components = listOf(
            RuntimeComponentStatus(
                id = "cli",
                label = "andy CLI",
                path = cli.absolutePath,
                installed = cli.isFile && cli.canExecute(),
                detail = installedVersion?.let { "release $it" },
            ),
            RuntimeComponentStatus(
                id = "andyd",
                label = "andyd launcher",
                path = andyd.absolutePath,
                installed = andyd.isFile && andyd.canExecute(),
            ),
            RuntimeComponentStatus(
                id = "andydJar",
                label = "andyd runtime",
                path = jar.absolutePath,
                installed = jar.isFile,
                detail = jar.takeIf { it.isFile }?.let { formatBytes(it.length()) },
            ),
            RuntimeComponentStatus(
                id = "tmux",
                label = "tmux",
                path = tmux.absolutePath,
                installed = tmux.exists(),
                detail = when {
                    !tmux.exists() -> null
                    FilesIsSymlink(tmux) -> "linked"
                    else -> "bundled"
                },
            ),
            RuntimeComponentStatus(
                id = "statusHook",
                label = "status hook",
                path = hook.absolutePath,
                installed = hook.isFile && hook.canExecute(),
            ),
            RuntimeComponentStatus(
                id = "piExtension",
                label = "Pi extension",
                path = pi.absolutePath,
                installed = pi.isFile,
            ),
            RuntimeComponentStatus(
                id = "opencodePlugin",
                label = "OpenCode plugin",
                path = opencode.absolutePath,
                installed = opencode.isFile,
            ),
            RuntimeComponentStatus(
                id = "skills",
                label = "orchestration skills",
                path = null,
                installed = OrchestrationSkillInstaller.isInstalled(home),
                detail = "${OrchestrationSkillInstaller.skills.size} skills",
            ),
        )

        var latestVersion: String? = null
        var latestUrl: String? = null
        if (checkLatest && platformTarget != null) {
            val release = fetchLatestRelease()
            latestVersion = normalizeReleaseVersion(release.tagName)
            latestUrl = release.htmlUrl
        }

        val coreMissing = !cli.isFile || !andyd.isFile || !jar.isFile
        val newerRelease = isNewerRelease(installed = installedVersion, latest = latestVersion)
        // Unknown local version with everything present is not treated as outdated —
        // the UI still offers "Install / reinstall from latest".
        val updateAvailable = platformTarget != null && latestVersion != null && (coreMissing || newerRelease)

        RuntimeBundleSnapshot(
            platformSupported = platformTarget != null,
            installedReleaseVersion = installedVersion,
            latestReleaseVersion = latestVersion,
            latestReleasePageUrl = latestUrl,
            updateAvailable = updateAvailable,
            components = components,
            pathHint = pathHintFor(cli),
            andydRunning = AndydProcess.isExternalDaemonLive(),
        )
    }

    private fun fetchLatestRelease(): GitHubReleaseDto {
        val url = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
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
        return parseGitHubRelease(response.body())
    }

    private fun downloadAsset(asset: GitHubAssetDto, target: File, onProgress: (Float?) -> Unit) {
        val request = HttpRequest.newBuilder(URI.create(asset.browserDownloadUrl))
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            target.delete()
            error("Download failed for ${asset.name}: HTTP ${response.statusCode()}")
        }
        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(asset.size)
            .takeIf { it > 0L }
        var downloaded = 0L
        onProgress(0f)
        target.outputStream().use { output ->
            response.body().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(totalBytes?.let { downloaded.toFloat() / it.toFloat() }?.coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun installExecutable(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
        source.delete()
        dest.setExecutable(true, false)
        if (isMacOs()) {
            runCatching {
                ProcessBuilder("codesign", "--force", "--sign", "-", dest.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
    }

    private fun writeAndydLauncher(dest: File) {
        dest.parentFile?.mkdirs()
        dest.writeText(ANDYD_LAUNCHER)
        dest.setExecutable(true, false)
    }

    private fun linkSystemTmuxIfNeeded() {
        val dest = File(binDir, "tmux")
        if (dest.exists()) return
        val system = resolveOnPath("tmux") ?: return
        runCatching {
            java.nio.file.Files.createSymbolicLink(dest.toPath(), File(system).toPath())
        }.onFailure {
            // Best-effort; agent sessions can still use PATH tmux.
        }
    }

    private fun writeOpenCodeCanonical(home: File) {
        val dest = File(home, ".andy/opencode/andy-status.js")
        dest.parentFile?.mkdirs()
        // Prefer the packaged installer content when a session-scoped install isn't needed.
        val content = runCatching {
            app.andy.desktop.service.agents.AndyOpenCodePluginInstaller.pluginContent
        }.getOrNull() ?: return
        if (dest.takeIf { it.isFile }?.readText() != content) {
            dest.writeText(content)
        }
    }

    private fun writeInstalledRelease(version: String, releasePageUrl: String?) {
        versionFile.parentFile?.mkdirs()
        val urlJson = releasePageUrl?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
        versionFile.writeText(
            """
            {
              "version": "$version",
              "releasePageUrl": $urlJson,
              "installedAtEpochMs": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n",
        )
    }

    private fun readInstalledReleaseVersion(): String? {
        val text = versionFile.takeIf { it.isFile }?.readText() ?: return null
        return runCatching {
            val parsed = SimpleJsonParser(text).parse() as? Map<*, *>
            parsed?.get("version") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun restartAndyd() {
        val pidPath = AndydProcess.pidPath()
        val pid = pidPath.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (pid != null) {
            ProcessHandle.of(pid).ifPresent { handle ->
                if (handle.isAlive) {
                    handle.destroy()
                    val deadline = System.currentTimeMillis() + 10_000
                    while (handle.isAlive && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100)
                    }
                    if (handle.isAlive) handle.destroyForcibly()
                }
            }
        }
        AndydProcess.removeStaleArtifacts()
        AndydProcess.tryLaunch()
        AndydProcess.waitForSocket(AndydProcess.socketPath(), 15_000)
    }

    private fun pathHintFor(cli: File): String? {
        if (!cli.isFile) {
            return "Install the CLI, then add ~/.andy/bin to your PATH."
        }
        val onPath = resolveOnPath("andy")
        return when {
            onPath == null -> "Add ~/.andy/bin to your PATH so `andy` resolves."
            File(onPath).canonicalFile != cli.canonicalFile ->
                "`andy` on PATH is $onPath — prefer ${cli.absolutePath}."
            else -> null
        }
    }

}

internal const val INSTALLED_RELEASE_FILE = "installed-release.json"

internal data class GitHubReleaseDto(
    val tagName: String,
    val htmlUrl: String,
    val assets: List<GitHubAssetDto>,
)

internal fun parseGitHubRelease(json: String): GitHubReleaseDto {
    val parsed = SimpleJsonParser(json).parse() as? Map<*, *> ?: error("Invalid JSON from GitHub")
    val tagName = parsed["tag_name"] as? String ?: ""
    val htmlUrl = parsed["html_url"] as? String ?: ""
    val rawAssets = parsed["assets"] as? List<*> ?: emptyList<Any>()
    val assets = rawAssets.mapNotNull { asset ->
        val assetMap = asset as? Map<*, *> ?: return@mapNotNull null
        GitHubAssetDto(
            name = assetMap["name"] as? String ?: "",
            browserDownloadUrl = assetMap["browser_download_url"] as? String ?: "",
            size = (assetMap["size"] as? Number)?.toLong() ?: 0L,
            digest = assetMap["digest"] as? String,
            state = assetMap["state"] as? String,
        )
    }
    return GitHubReleaseDto(tagName = tagName, htmlUrl = htmlUrl, assets = assets)
}

internal fun findAsset(assets: List<GitHubAssetDto>, pattern: Regex): GitHubAssetDto? =
    assets.firstOrNull { asset ->
        asset.browserDownloadUrl.isNotBlank() &&
            asset.state?.equals("uploaded", ignoreCase = true) != false &&
            pattern.containsMatchIn(asset.name)
    }

internal fun detectRuntimeInstallTarget(): String? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return when {
        "mac" in os || "darwin" in os -> when {
            arch == "aarch64" || arch == "arm64" -> "macos-arm64"
            else -> null
        }
        "linux" in os -> when {
            arch == "amd64" || arch == "x86_64" -> "linux-x86_64"
            else -> null
        }
        else -> null
    }
}

/** Keep calendar zero-padding (2026.0811.0422); only strip release/v prefixes. */
internal fun normalizeReleaseVersion(tagName: String): String? {
    val trimmed = tagName.trim()
        .removePrefix("release/")
        .removePrefix("v")
        .removePrefix("V")
    return trimmed.takeIf { SemanticVersion.parse(it) != null }
}

internal fun isNewerRelease(installed: String?, latest: String?): Boolean {
    val installedVersion = SemanticVersion.parse(installed) ?: return false
    val latestVersion = SemanticVersion.parse(latest) ?: return false
    return latestVersion > installedVersion
}

private fun isMacOs(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return "mac" in os || "darwin" in os
}

private fun resolveOnPath(name: String): String? {
    val path = System.getenv("PATH") ?: return null
    val sep = File.pathSeparatorChar
    return path.split(sep).asSequence()
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
}

private fun FilesIsSymlink(file: File): Boolean =
    java.nio.file.Files.isSymbolicLink(file.toPath())

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "${bytes / (1024L * 1024L)} MB"
}

private val ANDYD_LAUNCHER = """
#!/bin/sh
ANDY_HOME="${'$'}{ANDY_HOME:-${'$'}HOME/.andy}"
JAR="${'$'}{ANDY_ANDYD_JAR:-${'$'}ANDY_HOME/andyd/andyd.jar}"
JAVA="${'$'}{ANDY_JAVA:-java}"
if [ ! -f "${'$'}JAR" ]; then
  printf 'andyd runtime missing at %s\n' "${'$'}JAR" >&2
  printf 'Re-run install-andy.sh or update from Andy Settings → Updates.\n' >&2
  exit 1
fi
exec "${'$'}JAVA" \
  -Djdk.lang.Process.launchMechanism=FORK \
  -Dapple.awt.UIElement=true \
  -Djava.awt.headless=true \
  -jar "${'$'}JAR" "${'$'}@"
""".trimIndent() + "\n"
