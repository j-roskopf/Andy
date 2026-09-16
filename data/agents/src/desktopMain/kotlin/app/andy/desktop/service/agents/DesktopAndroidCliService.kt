package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.service.AndroidCliService
import app.andy.service.AndroidCliSnapshot
import app.andy.service.AndroidCliState
import app.andy.service.AndroidSkillCatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Installs Google's Android CLI and drives `android skills` to add the official
 * Android skills (https://github.com/android/skills) into the skill directories
 * of every agent detected on this machine.
 *
 * The binary is downloaded straight from `dl.google.com` (the same artifact the
 * official `install.sh` fetches) rather than piping a remote script, so the
 * install stays observable and testable.
 */
class DesktopAndroidCliService(
    private val home: File = File(System.getProperty("user.home")),
    private val userAgent: String = "Andy",
    private val installTarget: String? = detectAndroidCliInstallTarget(),
    private val installDir: File = File(home, ".local/bin"),
    private val catalogUrl: String = DEFAULT_CATALOG_URL,
) : AndroidCliService {

    private val mutableState = MutableStateFlow<AndroidCliState>(AndroidCliState.Idle)
    override val state: StateFlow<AndroidCliState> = mutableState.asStateFlow()

    /** The skill catalog is stable; fetch it once per app session. */
    @Volatile
    private var cachedCatalog: List<AndroidSkillCatalogEntry>? = null

    @Volatile
    private var lastCatalogError: String? = null

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    override suspend fun refresh() {
        if (mutableState.value is AndroidCliState.Busy) return
        mutableState.value = AndroidCliState.Checking
        runCatching { probe(refreshCatalog = true) }
            .onSuccess { mutableState.value = AndroidCliState.Ready(it) }
            .onFailure { error ->
                mutableState.value = AndroidCliState.Failed(
                    message = error.message ?: "Couldn't check for the Android CLI.",
                    snapshot = runCatching { probe(refreshCatalog = false) }.getOrNull(),
                )
            }
    }

    override suspend fun installCli() {
        if (mutableState.value is AndroidCliState.Busy) return
        val prior = currentSnapshot()
        val target = installTarget
        if (target == null) {
            mutableState.value = AndroidCliState.Failed(
                message = "The Android CLI does not ship a binary for this platform.",
                snapshot = prior,
            )
            return
        }

        mutableState.value = AndroidCliState.Busy(prior, "Downloading Android CLI…", 0f)
        withContext(Dispatchers.IO) {
            runCatching {
                installDir.mkdirs()
                val staged = File.createTempFile("android-cli-", ".part", installDir)
                try {
                    downloadTo(
                        uri = URI.create("$DOWNLOAD_BASE/$target/$BINARY_NAME"),
                        target = staged,
                    ) { fraction ->
                        mutableState.value = AndroidCliState.Busy(prior, "Downloading Android CLI…", fraction)
                    }
                    val dest = File(installDir, BINARY_NAME)
                    staged.copyTo(dest, overwrite = true)
                    dest.setReadable(true, false)
                    dest.setExecutable(true, false)
                } finally {
                    staged.delete()
                }
            }.onSuccess {
                mutableState.value = AndroidCliState.Ready(probe(refreshCatalog = false))
            }.onFailure { error ->
                mutableState.value = AndroidCliState.Failed(
                    message = error.message ?: "The Android CLI install failed.",
                    snapshot = prior,
                )
            }
        }
    }

    override suspend fun installAllSkills() {
        runSkillsCommand(
            label = "Installing all Android skills…",
            arguments = listOf("skills", "add", "--all"),
            activeSkill = null,
        )
    }

    override suspend fun installSkill(skillName: String) {
        val known = cachedCatalog?.any { it.name == skillName } == true
        if (!known) {
            mutableState.value = AndroidCliState.Failed(
                message = "Unknown Android skill \"$skillName\".",
                snapshot = currentSnapshot(),
            )
            return
        }
        runSkillsCommand(
            label = "Installing $skillName…",
            arguments = listOf("skills", "add", skillName),
            activeSkill = skillName,
        )
    }

    private suspend fun runSkillsCommand(
        label: String,
        arguments: List<String>,
        activeSkill: String?,
    ) {
        if (mutableState.value is AndroidCliState.Busy) return
        val prior = currentSnapshot()
        val cli = resolveCli()
        if (cli == null) {
            mutableState.value = AndroidCliState.Failed(
                message = "Install the Android CLI before adding skills.",
                snapshot = prior,
            )
            return
        }

        val commandLine = (listOf(cli.absolutePath) + arguments).joinToString(" ")
        val log = mutableListOf<String>()
        fun publish() {
            mutableState.value = AndroidCliState.Busy(
                snapshot = prior,
                message = label,
                activeSkill = activeSkill,
                command = commandLine,
                log = synchronized(log) { log.toList() },
            )
        }

        publish()
        withContext(Dispatchers.IO) {
            runCatching {
                runProcessStreaming(listOf(cli.absolutePath) + arguments, SKILLS_TIMEOUT_SECONDS) { line ->
                    synchronized(log) {
                        log += line
                        while (log.size > MAX_LOG_LINES) log.removeAt(0)
                    }
                    publish()
                }
            }.onSuccess {
                mutableState.value = AndroidCliState.Ready(probe(refreshCatalog = false))
            }.onFailure { error ->
                mutableState.value = AndroidCliState.Failed(
                    message = error.message ?: "The Android skills command failed.",
                    snapshot = prior,
                    command = commandLine,
                    log = synchronized(log) { log.toList() },
                )
            }
        }
    }

    private suspend fun probe(refreshCatalog: Boolean): AndroidCliSnapshot = withContext(Dispatchers.IO) {
        val cli = resolveCli()
        val catalog = catalog(refreshCatalog)
        AndroidCliSnapshot(
            platformSupported = installTarget != null,
            installTarget = installTarget,
            cliInstalled = cli != null,
            cliPath = cli?.absolutePath,
            availableSkills = catalog,
            installedSkills = installedSkillNames(catalog),
            catalogError = lastCatalogError,
            pathHint = pathHintFor(cli),
        )
    }

    private suspend fun catalog(forceRefresh: Boolean): List<AndroidSkillCatalogEntry> {
        cachedCatalog?.takeIf { !forceRefresh }?.let { return it }
        return runCatching { fetchCatalog() }
            .onSuccess {
                cachedCatalog = it
                lastCatalogError = null
            }
            .getOrElse { error ->
                lastCatalogError = error.message ?: "Couldn't load the Android skills catalog."
                cachedCatalog ?: emptyList()
            }
    }

    private suspend fun fetchCatalog(): List<AndroidSkillCatalogEntry> {
        val paths = parseMarketplaceSkillPaths(httpGetText(catalogUrl))
        if (paths.isEmpty()) return emptyList()
        return coroutineScope {
            paths.chunked(CATALOG_FETCH_CONCURRENCY).flatMap { chunk ->
                chunk.map { path -> async { catalogEntry(path) } }.awaitAll()
            }
        }
    }

    private fun catalogEntry(path: String): AndroidSkillCatalogEntry {
        val directoryName = path.substringAfterLast('/')
        val category = path.substringBefore('/').takeIf { it.isNotBlank() && it != path }
        val skillMarkdown = runCatching { httpGetText("$RAW_SKILLS_BASE/$path/SKILL.md") }.getOrNull()
        val (name, description) = parseSkillFrontmatter(skillMarkdown)
        return AndroidSkillCatalogEntry(
            name = name ?: directoryName,
            description = description,
            category = category,
        )
    }

    private fun installedSkillNames(catalog: List<AndroidSkillCatalogEntry>): Set<String> {
        val names = catalog.mapTo(HashSet()) { it.name }
        if (names.isEmpty()) return emptySet()
        val roots = buildList {
            add(File(home, ".agents/skills"))
            add(File(home, ".gemini/antigravity/skills"))
            AgentKind.entries.forEach { kind ->
                addAll(skillRootsFor(kind, workspace = null, home = home))
            }
        }.distinct()

        val installed = linkedSetOf<String>()
        roots.forEach { root ->
            root.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name in names && File(child, "SKILL.md").isFile) {
                    installed += child.name
                }
            }
        }
        return installed
    }

    private fun resolveCli(): File? {
        val onPath = System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.map { File(it, BINARY_NAME) }
            ?.firstOrNull { it.isFile && it.canExecute() }
        if (onPath != null) return onPath
        return File(installDir, BINARY_NAME).takeIf { it.isFile && it.canExecute() }
    }

    private fun pathHintFor(cli: File?): String? {
        if (cli == null) return null
        val path = System.getenv("PATH").orEmpty()
        val onPath = path.split(File.pathSeparatorChar).any { File(it).absolutePath == cli.parentFile?.absolutePath }
        return if (onPath) null else "Add ${cli.parentFile?.absolutePath} to PATH so agents can run `$BINARY_NAME`."
    }

    private fun currentSnapshot(): AndroidCliSnapshot? = when (val current = mutableState.value) {
        is AndroidCliState.Ready -> current.snapshot
        is AndroidCliState.Busy -> current.snapshot
        is AndroidCliState.Failed -> current.snapshot
        else -> null
    }

    private fun downloadTo(uri: URI, target: File, onProgress: (Float) -> Unit) {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(10))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            error("Android CLI download failed: HTTP ${response.statusCode()}")
        }
        val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L).takeIf { it > 0L }
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
                    totalBytes?.let { onProgress((downloaded.toFloat() / it.toFloat()).coerceIn(0f, 1f)) }
                }
            }
        }
    }

    private fun httpGetText(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("HTTP ${response.statusCode()} for $url")
        }
        return response.body()
    }

    /**
     * Runs a command and streams combined stdout/stderr line-by-line to [onLine]
     * so the settings panel can show live progress. Throws on timeout or a
     * non-zero exit, with the last non-blank output line as the message.
     */
    private fun runProcessStreaming(
        command: List<String>,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val tail = java.util.ArrayDeque<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().use { input ->
                input.lineSequence().forEach { line ->
                    synchronized(tail) {
                        tail.addLast(line)
                        while (tail.size > MAX_LOG_LINES) tail.removeFirst()
                    }
                    onLine(line)
                }
            }
        }.apply {
            isDaemon = true
            name = "android-cli-output"
        }
        reader.start()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1_000)
            error("`android ${command.drop(1).joinToString(" ")}` timed out.")
        }
        reader.join(2_000)
        if (process.exitValue() != 0) {
            val last = synchronized(tail) { tail.lastOrNull { it.isNotBlank() } }
            error(last ?: "`android ${command.drop(1).joinToString(" ")}` failed (exit ${process.exitValue()}).")
        }
    }

    private companion object {
        const val BINARY_NAME = "android"
        const val DOWNLOAD_BASE = "https://dl.google.com/android/cli/latest"
        const val RAW_SKILLS_BASE = "https://raw.githubusercontent.com/android/skills/main"
        const val DEFAULT_CATALOG_URL = "$RAW_SKILLS_BASE/.claude-plugin/marketplace.json"
        const val SKILLS_TIMEOUT_SECONDS = 600L
        const val CATALOG_FETCH_CONCURRENCY = 8
        const val MAX_LOG_LINES = 200
    }
}

/** Maps the host OS/arch onto the directory names used by the Android CLI download site. */
internal fun detectAndroidCliInstallTarget(): String? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    return when {
        "mac" in os || "darwin" in os -> if (isArm) "darwin_arm64" else "darwin_x86_64"
        "linux" in os -> if (arch == "amd64" || arch == "x86_64") "linux_x86_64" else null
        else -> null
    }
}

/**
 * Reads the `skills` paths out of the android/skills marketplace manifest
 * (`plugins[].skills[]`, each a `./category/sub/skill` path).
 */
internal fun parseMarketplaceSkillPaths(json: String): List<String> {
    val plugins = runCatching { Json.parseToJsonElement(json).jsonObject["plugins"]?.jsonArray }
        .getOrNull() ?: return emptyList()
    val paths = mutableListOf<String>()
    plugins.forEach { plugin ->
        plugin.jsonObject["skills"]?.jsonArray?.forEach { entry ->
            val path = runCatching { entry.jsonPrimitive.content }.getOrNull()
                ?.removePrefix("./")
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
            if (path != null) paths += path
        }
    }
    return paths.distinct()
}

/**
 * Extracts `name` and `description` from a SKILL.md YAML frontmatter block.
 * Descriptions are often folded across indented continuation lines.
 */
internal fun parseSkillFrontmatter(markdown: String?): Pair<String?, String?> {
    if (markdown.isNullOrBlank()) return null to null
    val lines = markdown.lineSequence().take(80).toList()
    val start = lines.indexOfFirst { it.trim() == "---" }
    if (start < 0) return null to null
    val rest = lines.drop(start + 1)
    val end = rest.indexOfFirst { it.trim() == "---" }
    val block = if (end < 0) rest else rest.take(end)

    var name: String? = null
    val description = mutableListOf<String>()
    var capturingDescription = false
    block.forEach { raw ->
        val isKeyLine = raw.isNotEmpty() &&
            !raw.first().isWhitespace() &&
            raw.substringBefore(':').let { it.isNotBlank() && it.none(Char::isWhitespace) }
        if (isKeyLine) {
            capturingDescription = false
            val key = raw.substringBefore(':').trim()
            val value = raw.substringAfter(':', "").trim().trim('"', '\'')
            when (key) {
                "name" -> name = value.takeIf { it.isNotBlank() }
                "description" -> {
                    capturingDescription = true
                    if (value.isNotBlank() && value != ">" && value != "|") description += value
                }
            }
        } else if (capturingDescription && raw.isNotEmpty() && raw.first().isWhitespace()) {
            description += raw.trim()
        }
    }
    return name to description.joinToString(" ").takeIf { it.isNotBlank() }
}
