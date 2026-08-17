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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) : CliUpdateCheckService {

    private val cliStatuses: StateFlow<List<AgentCliStatus>> get() = agentRuns.cliStatuses

    private val mutableOutdated = MutableStateFlow<List<CliUpdateInfo>>(emptyList())
    override val outdated: StateFlow<List<CliUpdateInfo>> = mutableOutdated.asStateFlow()

    private val mutableUpdating = MutableStateFlow<Set<AgentKind>>(emptySet())
    override val updating: StateFlow<Set<AgentKind>> = mutableUpdating.asStateFlow()

    private val dismissed = mutableSetOf<String>()
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
        mutableOutdated.value = mutableOutdated.value.filterNot { it.kind == kind && it.latestVersion == latestVersion }
    }

    override fun startUpdate(item: CliUpdateInfo): String? {
        if (item.kind in mutableUpdating.value) return null
        val homeDir = System.getProperty("user.home") ?: return null
        val project = ActionProject(id = "cli-update", name = item.kind.label, contextDir = homeDir)
        val action = ProjectAction(
            id = "cli-update-${item.kind.name}",
            name = "Update ${item.kind.label}",
            command = "${shellQuote(item.binaryPath)} ${updateSubcommand(item.kind)}",
        )
        val runId = actionRuns.run(project, action)
        mutableUpdating.value += item.kind
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
            mutableUpdating.value -= item.kind
        }
    }

    private fun updateSubcommand(kind: AgentKind): String = when (kind) {
        AgentKind.OpenCode -> "upgrade"
        else -> "update"
    }

    private fun dismissalKey(kind: AgentKind, latestVersion: String) = "${kind.name}:$latestVersion"

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
