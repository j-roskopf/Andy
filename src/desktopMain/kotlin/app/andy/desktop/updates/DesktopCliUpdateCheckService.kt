package app.andy.desktop.updates

import app.andy.model.ActionProject
import app.andy.model.ActionRunStatus
import app.andy.model.AgentCliStatus
import app.andy.model.AgentKind
import app.andy.model.ProjectAction
import app.andy.service.ActionRunService
import app.andy.service.AgentRunService
import app.andy.service.CliUpdateCheckService
import app.andy.service.CliUpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Checks provider CLI versions against the npm registry. Only providers published to a
 * well-known npm package are checkable (Cursor, Antigravity, Pi, Hermes, OpenClaw have no
 * public registry to compare against, so they're skipped rather than guessed at).
 */
class DesktopCliUpdateCheckService(
    private val agentRuns: AgentRunService,
    private val actionRuns: ActionRunService,
    private val scope: CoroutineScope,
    private val userAgent: String = "Andy CLI update check",
    private val checkIntervalMillis: Long = 6 * 60 * 60 * 1000L,
    private val dismissalsFile: File = File(
        System.getProperty("user.home"),
        ".andy/cli-update-dismissals.json",
    ),
) : CliUpdateCheckService {

    private val cliStatuses: StateFlow<List<AgentCliStatus>> get() = agentRuns.cliStatuses

    private val mutableOutdated = MutableStateFlow<List<CliUpdateInfo>>(emptyList())
    override val outdated: StateFlow<List<CliUpdateInfo>> = mutableOutdated.asStateFlow()

    private val mutableUpdating = MutableStateFlow<Set<AgentKind>>(emptySet())
    override val updating: StateFlow<Set<AgentKind>> = mutableUpdating.asStateFlow()

    private val dismissalsJson = Json { ignoreUnknownKeys = true }
    private val dismissed = loadDismissals().toMutableSet()
    private val latestCache = mutableMapOf<AgentKind, Pair<String, Long>>()

    override suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val checkable = cliStatuses.value.filter { it.available && it.kind in NpmPackageNames }

        val results = coroutineScope {
            checkable.map { status ->
                async {
                    val cached = latestCache[status.kind]
                    val latest = if (cached != null && now - cached.second < checkIntervalMillis) {
                        cached.first
                    } else {
                        fetchLatestNpmVersion(NpmPackageNames.getValue(status.kind))
                            ?.also { latestCache[status.kind] = it to now }
                    }
                    status to latest
                }
            }.awaitAll()
        }

        mutableOutdated.value = results.mapNotNull { (status, latest) ->
            val binaryPath = status.binaryPath ?: return@mapNotNull null
            val installed = extractSemanticVersion(status.version) ?: return@mapNotNull null
            val latestVersion = extractSemanticVersion(latest) ?: return@mapNotNull null
            if (latestVersion <= installed) return@mapNotNull null
            val key = dismissalKey(status.kind, latestVersion.toString())
            if (key in dismissed) return@mapNotNull null
            CliUpdateInfo(status.kind, installed.toString(), latestVersion.toString(), binaryPath)
        }
    }

    override fun dismiss(kind: AgentKind, latestVersion: String) {
        dismissed += dismissalKey(kind, latestVersion)
        persistDismissals()
        mutableOutdated.update { current ->
            current.filterNot { it.kind == kind && it.latestVersion == latestVersion }
        }
    }

    override fun startUpdate(item: CliUpdateInfo): String? {
        val homeDir = System.getProperty("user.home") ?: return null
        var claimed = false
        mutableUpdating.update { current ->
            if (item.kind in current) {
                current
            } else {
                claimed = true
                current + item.kind
            }
        }
        if (!claimed) return null
        val project = ActionProject(id = "cli-update", name = item.kind.label, contextDir = homeDir)
        val action = ProjectAction(
            id = "cli-update-${item.kind.name}",
            name = "Update ${item.kind.label}",
            command = "${shellQuote(item.binaryPath)} ${updateSubcommand(item.kind)}",
        )
        val runId = actionRuns.run(project, action)
        scope.launch { pollUntilUpdated(item, runId) }
        return runId
    }

    /**
     * Runs are persistent login shells (the update command is just typed into them), so there's
     * no "process exited" signal to await. Poll the CLI's own reported version instead — the
     * same re-source [AgentRunService.refreshCliStatuses] does after any CLI install/repair —
     * until it moves past the outdated version, the shell closes, or we give up.
     */
    private suspend fun pollUntilUpdated(item: CliUpdateInfo, runId: String) {
        val deadline = System.currentTimeMillis() + PollTimeoutMillis
        try {
            while (System.currentTimeMillis() < deadline) {
                delay(PollIntervalMillis)
                agentRuns.refreshCliStatuses()
                checkForUpdates()
                val stillOutdated = mutableOutdated.value.any { it.kind == item.kind }
                if (!stillOutdated) return
                val runStatus = actionRuns.running.value.firstOrNull { it.runId == runId }?.status
                if (runStatus != ActionRunStatus.Starting && runStatus != ActionRunStatus.Running) return
            }
        } finally {
            mutableUpdating.update { it - item.kind }
        }
    }

    private fun updateSubcommand(kind: AgentKind): String = when (kind) {
        AgentKind.OpenCode -> "upgrade"
        else -> "update"
    }

    private fun dismissalKey(kind: AgentKind, latestVersion: String) = "${kind.name}:$latestVersion"

    private fun loadDismissals(): Set<String> {
        if (!dismissalsFile.isFile) return emptySet()
        val text = runCatching { dismissalsFile.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return emptySet()
        return runCatching {
            dismissalsJson.decodeFromString<List<String>>(text).toSet()
        }.getOrElse { emptySet() }
    }

    private fun persistDismissals() {
        runCatching {
            dismissalsFile.parentFile?.mkdirs()
            dismissalsFile.writeText(dismissalsJson.encodeToString(dismissed.sorted()) + "\n")
        }
    }

    private fun fetchLatestNpmVersion(packageName: String): String? = runCatching {
        val encoded = URLEncoder.encode(packageName, "UTF-8")
        val request = HttpRequest.newBuilder(URI.create("https://registry.npmjs.org/$encoded/latest"))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(8))
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val parsed = SimpleJsonParser(response.body()).parse() as? Map<*, *> ?: return null
        parsed["version"] as? String
    }.getOrNull()

    private companion object {
        const val PollIntervalMillis = 3_000L
        const val PollTimeoutMillis = 3 * 60 * 1000L
        val NpmPackageNames: Map<AgentKind, String> = mapOf(
            AgentKind.ClaudeCode to "@anthropic-ai/claude-code",
            AgentKind.Codex to "@openai/codex",
            AgentKind.OpenCode to "opencode-ai",
        )
    }
}

/** CLI `--version` output varies ("2.1.233 (Claude Code)", "codex-cli 0.147.0-alpha.6.5"); pull the first x.y.z. */
private fun extractSemanticVersion(raw: String?): SemanticVersion? {
    val match = Regex("""\d+\.\d+\.\d+""").find(raw.orEmpty()) ?: return null
    return SemanticVersion.parse(match.value)
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
