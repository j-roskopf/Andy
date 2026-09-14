package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.OpenRouterAttribution
import app.andy.model.WorkspaceState
import app.andy.model.isModelBackend
import app.andy.model.localModelBaseUrl
import app.andy.model.localModelBearerToken
import app.andy.model.parseOpenAiCompatModels
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class LocalModelProbe(
    private val localClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(400))
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val cloudClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val openRouterApiKey: () -> String? = { OpenRouterCredentialStore.load() },
) {
    fun query(workspace: WorkspaceState): Map<AgentKind, List<AgentModelOption>> =
        AgentKind.entries.filter { it.isModelBackend }.mapNotNull { backend ->
            query(backend, workspace)?.let { backend to it }
        }.toMap()

    fun reachable(workspace: WorkspaceState): Map<AgentKind, Boolean> =
        AgentKind.entries.filter { it.isModelBackend }.associateWith { backend ->
            query(backend, workspace) != null
        }

    fun query(backend: AgentKind, workspace: WorkspaceState): List<AgentModelOption>? {
        if (backend == AgentKind.OpenRouter) {
            val key = openRouterApiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Key present → backend is usable (free-text model works even if /models is slow).
            return probeModels(backend, workspace, key) ?: emptyList()
        }
        return probeModels(backend, workspace, bearer = null)
    }

    private fun probeModels(
        backend: AgentKind,
        workspace: WorkspaceState,
        bearer: String?,
    ): List<AgentModelOption>? =
        runCatching {
            val cloud = backend == AgentKind.OpenRouter
            val client = if (cloud) cloudClient else localClient
            val url = workspace.localModelBaseUrl(backend).trimEnd('/') + "/models"
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(if (cloud) 20_000 else 800))
                .header("Accept", "application/json")
                .GET()
            if (cloud) {
                request.header("HTTP-Referer", OpenRouterAttribution.HttpReferer)
                request.header("X-OpenRouter-Title", OpenRouterAttribution.Title)
            }
            val token = bearer ?: workspace.localModelBearerToken(backend, openRouterApiKey())
            token?.let { request.header("Authorization", "Bearer $it") }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            parseOpenAiCompatModels(body, backend).takeIf { it.isNotEmpty() }
                ?: emptyList<AgentModelOption>().takeIf { body.isNotBlank() }
        }.getOrNull()
}
