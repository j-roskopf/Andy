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
        .connectTimeout(Duration.ofSeconds(2))
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

    fun query(backend: AgentKind, workspace: WorkspaceState): List<AgentModelOption>? {
        val url = workspace.localModelBaseUrl(backend).trimEnd('/') + "/models"
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json")
            .GET()
        workspace.localModelBearerToken(backend)?.let { token ->
            request.header("Authorization", "Bearer $token")
        }
        val body = runCatching {
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return null
            response.body()
        }.getOrNull() ?: return null
        return parseOpenAiCompatModels(body, backend).takeIf { it.isNotEmpty() }
            ?: emptyList<AgentModelOption>().takeIf { body.isNotBlank() }
    }
}
