package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.WorkspaceState
import app.andy.model.isLocalModelBackend
import app.andy.model.localModelBaseUrl
import app.andy.model.localModelBearerToken
import app.andy.model.parseOpenAiCompatModels
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class LocalModelProbe(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(400))
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun query(workspace: WorkspaceState): Map<AgentKind, List<AgentModelOption>> =
        AgentKind.entries.filter { it.isLocalModelBackend }.mapNotNull { backend ->
            query(backend, workspace)?.let { backend to it }
        }.toMap()

    fun reachable(workspace: WorkspaceState): Map<AgentKind, Boolean> =
        AgentKind.entries.filter { it.isLocalModelBackend }.associateWith { backend ->
            query(backend, workspace) != null
        }

    fun query(backend: AgentKind, workspace: WorkspaceState): List<AgentModelOption>? =
        runCatching {
            val url = workspace.localModelBaseUrl(backend).trimEnd('/') + "/models"
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(800))
                .header("Accept", "application/json")
                .GET()
            workspace.localModelBearerToken(backend)?.let { token ->
                request.header("Authorization", "Bearer $token")
            }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            parseOpenAiCompatModels(body, backend).takeIf { it.isNotEmpty() }
                ?: emptyList<AgentModelOption>().takeIf { body.isNotBlank() }
        }.getOrNull()
}
