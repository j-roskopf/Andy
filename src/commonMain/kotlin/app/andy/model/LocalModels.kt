package app.andy.model

/** Coding-agent CLI that actually owns the loop for an Ollama / LM Studio chat. */
enum class LocalAgentRuntime(val agent: AgentKind, val label: String) {
    OpenCode(AgentKind.OpenCode, "OpenCode"),
    Pi(AgentKind.Pi, "Pi"),
    Goose(AgentKind.Goose, "Goose"),
}

const val DefaultOllamaBaseUrl = "http://127.0.0.1:11434/v1"
const val DefaultLmStudioBaseUrl = "http://127.0.0.1:1234/v1"

val AgentKind.isLocalModelBackend: Boolean
    get() = this == AgentKind.Ollama || this == AgentKind.LMStudio

val AgentKind.hasVendorCli: Boolean
    get() = !isLocalModelBackend

/** OpenCode / Goose / Pi provider id written into `--model provider/id`. */
val AgentKind.localModelProviderId: String
    get() = when (this) {
        AgentKind.Ollama -> "ollama"
        AgentKind.LMStudio -> "lmstudio"
        else -> error("${label} is not a local model backend")
    }

fun AgentKind.runtimeKind(localRuntime: LocalAgentRuntime?): AgentKind =
    if (isLocalModelBackend) (localRuntime ?: LocalAgentRuntime.OpenCode).agent else this

fun AgentTask.runtimeKind(): AgentKind = agent.runtimeKind(localRuntime)

fun AgentTaskDraft.runtimeKind(): AgentKind = agent.runtimeKind(localRuntime)

fun ProjectAgentProfile.runtimeKind(): AgentKind = agent.runtimeKind(localRuntime)

fun parseLocalAgentRuntime(raw: String?): LocalAgentRuntime? =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
        LocalAgentRuntime.entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) ||
                it.agent.name.equals(value, ignoreCase = true) ||
                it.agent.cliName.equals(value, ignoreCase = true) ||
                it.label.equals(value, ignoreCase = true)
        }
    }

/**
 * Composer / MCP identity for one picker row.
 * Vendor agents stay a single row; local backends expand to one row per runtime.
 */
data class AgentPickerOption(
    val agent: AgentKind,
    val localRuntime: LocalAgentRuntime? = null,
) {
    val label: String
        get() = if (localRuntime != null) "${agent.label} · ${localRuntime.label}" else agent.label

    val runtimeKind: AgentKind get() = agent.runtimeKind(localRuntime)
}

fun agentPickerOptions(): List<AgentPickerOption> =
    AgentKind.entries.filter { it.hasVendorCli }.map { AgentPickerOption(it) } +
        AgentKind.entries.filter { it.isLocalModelBackend }.flatMap { backend ->
            LocalAgentRuntime.entries.map { AgentPickerOption(backend, it) }
        }

fun prefixedLocalModelId(backend: AgentKind, rawId: String): String {
    val id = rawId.trim()
    if (id.isEmpty()) return id
    val prefix = "${backend.localModelProviderId}/"
    return if (id.startsWith(prefix, ignoreCase = true)) id else prefix + id
}

/** Catalog / API id with Andy's `ollama/` or `lmstudio/` prefix removed. */
fun localModelIdWithoutProviderPrefix(backend: AgentKind, stored: String): String {
    val id = stored.trim()
    val prefix = "${backend.localModelProviderId}/"
    return if (id.startsWith(prefix, ignoreCase = true)) id.substring(prefix.length) else id
}

/** Goose/Ollama hosts are origins; Andy Settings URLs are OpenAI-compat `…/v1`. */
fun openaiCompatUrlToProviderHost(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1", ignoreCase = true)) trimmed.dropLast(3).trimEnd('/') else trimmed
}

fun WorkspaceState.localModelBaseUrl(backend: AgentKind): String = when (backend) {
    AgentKind.Ollama -> ollamaBaseUrl.trim().ifBlank { DefaultOllamaBaseUrl }
    AgentKind.LMStudio -> lmStudioBaseUrl.trim().ifBlank { DefaultLmStudioBaseUrl }
    else -> error("${backend.label} is not a local model backend")
}

fun WorkspaceState.localModelBearerToken(backend: AgentKind): String? = when (backend) {
    AgentKind.Ollama -> ollamaBearerToken.trim().takeIf { it.isNotEmpty() }
    AgentKind.LMStudio -> lmStudioBearerToken.trim().takeIf { it.isNotEmpty() }
    else -> null
}

fun parseOpenAiCompatModels(output: String, backend: AgentKind): List<AgentModelOption> {
    val rows = parseProviderJsonModels(output)
    return rows.map { (id, label) ->
        AgentModelOption(
            id = prefixedLocalModelId(backend, id),
            label = label,
            efforts = emptyList(),
        )
    }.distinctBy { it.id }
}

/** Goose is ready as a local runtime if the binary exists; `goose configure` is not required. */
fun AgentCliStatus.readyForLocalRuntime(): Boolean =
    if (kind == AgentKind.Goose) available || acpReady else ready

fun localModelComboReady(
    backendReachable: Boolean,
    runtimeStatus: AgentCliStatus?,
): Boolean = backendReachable && (runtimeStatus?.readyForLocalRuntime() == true)

fun AgentPickerOption.comboReady(
    cliStatuses: List<AgentCliStatus>,
    localBackends: Map<AgentKind, Boolean>,
): Boolean {
    if (agent.isLocalModelBackend) {
        return localModelComboReady(
            backendReachable = localBackends[agent] == true,
            runtimeStatus = cliStatuses.firstOrNull { it.kind == runtimeKind },
        )
    }
    val status = cliStatuses.firstOrNull { it.kind == agent }
    return status?.ready == true || cliStatuses.isEmpty()
}

/** Whether discovery has found at least one provider the new-chat composer can launch. */
fun hasAvailableAgentProvider(
    cliStatuses: List<AgentCliStatus>,
    localBackends: Map<AgentKind, Boolean>,
): Boolean = agentPickerOptions().any { it.comboReady(cliStatuses, localBackends) }

fun AgentTaskDraft.localModelLaunchError(): String? {
    if (!agent.isLocalModelBackend) return null
    if (localRuntime == null) return "runtime is required for ${agent.label} (OpenCode, Pi, or Goose)"
    if (model.isNullOrBlank()) return "a model is required for ${agent.label}"
    return null
}
