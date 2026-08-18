package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.WorkspaceState
import app.andy.model.isLocalModelBackend
import app.andy.model.localModelBaseUrl
import app.andy.model.localModelBearerToken
import app.andy.model.localModelIdWithoutProviderPrefix
import app.andy.model.localModelProviderId
import app.andy.model.modelForCli
import app.andy.model.openaiCompatUrlToProviderHost
import app.andy.model.runtimeKind
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Andy-owned spawn overlay so OpenCode/Pi talk to Ollama/LM Studio without rewriting
 * the user's global provider catalog. Goose is env-only.
 */
internal object LocalModelSidecar {
    private val json = Json { prettyPrint = true }

    fun rootDir(home: File = File(System.getProperty("user.home"))): File =
        File(home, ".andy/local-models")

    fun envFor(task: AgentTask, workspace: WorkspaceState, home: File = File(System.getProperty("user.home"))): Map<String, String> {
        if (!task.agent.isLocalModelBackend) return emptyMap()
        val runtime = task.runtimeKind()
        val baseUrl = workspace.localModelBaseUrl(task.agent)
        val token = workspace.localModelBearerToken(task.agent)
        val host = openaiCompatUrlToProviderHost(baseUrl)
        return when (runtime) {
            AgentKind.Goose -> buildMap {
                put(
                    if (task.agent == AgentKind.Ollama) "OLLAMA_HOST" else "LMSTUDIO_HOST",
                    host,
                )
                token?.let { put("GOOSE_PROVIDER__API_KEY", it) }
            }
            AgentKind.OpenCode -> {
                val file = writeOpenCodeConfig(task.agent, baseUrl, token, home, task.modelForCli())
                mapOf(
                    "OPENCODE_CONFIG" to file.absolutePath,
                    "OPENCODE_CONFIG_CONTENT" to file.readText(),
                )
            }
            AgentKind.Pi -> {
                val agentDir = writePiAgentDir(task, baseUrl, token, home)
                buildMap {
                    put("PI_CODING_AGENT_DIR", agentDir.absolutePath)
                    if (task.agent == AgentKind.Ollama) {
                        put("OLLAMA_HOST", host)
                    }
                    put("OPENAI_API_KEY", token ?: "andy-local")
                }
            }
            else -> emptyMap()
        }
    }

    internal fun writeOpenCodeConfig(
        backend: AgentKind,
        baseUrl: String,
        token: String?,
        home: File,
        model: String? = null,
    ): File {
        val dir = rootDir(home).apply { mkdirs() }
        val file = File(dir, "opencode-${backend.localModelProviderId}.json")
        val providerId = backend.localModelProviderId
        val selected = model?.trim()?.takeIf { it.isNotBlank() }
        val catalogId = selected?.let { localModelIdWithoutProviderPrefix(backend, it) }?.takeIf { it.isNotBlank() }
        val provider = buildJsonObject {
            put("npm", JsonPrimitive("@ai-sdk/openai-compatible"))
            put("name", JsonPrimitive(backend.label))
            put(
                "options",
                buildJsonObject {
                    put("baseURL", JsonPrimitive(baseUrl.trimEnd('/')))
                    put("apiKey", JsonPrimitive(token ?: "andy-local"))
                },
            )
            if (catalogId != null) {
                put(
                    "models",
                    buildJsonObject {
                        put(
                            catalogId,
                            buildJsonObject {
                                put("name", JsonPrimitive(catalogId))
                            },
                        )
                    },
                )
            }
        }
        val body = buildJsonObject {
            put("\$schema", "https://opencode.ai/config.json")
            selected?.let {
                put("model", JsonPrimitive(it))
                put("small_model", JsonPrimitive(it))
            }
            put("provider", buildJsonObject { put(providerId, provider) })
        }
        file.writeText(json.encodeToString(JsonObject.serializer(), body) + "\n")
        return file
    }

    internal fun writePiAgentDir(
        task: AgentTask,
        baseUrl: String,
        token: String?,
        home: File,
    ): File {
        val backend = task.agent
        val agentDir = File(rootDir(home), "pi-${backend.localModelProviderId}-agent").apply { mkdirs() }
        val providerId = backend.localModelProviderId
        val modelId = task.model
            ?.let { localModelIdWithoutProviderPrefix(backend, it) }
            ?.takeIf { it.isNotBlank() }
            ?: "default"
        val localProvider = buildJsonObject {
            put("baseUrl", JsonPrimitive(baseUrl.trimEnd('/')))
            put("api", JsonPrimitive("openai-completions"))
            put("apiKey", JsonPrimitive(token ?: "andy-local"))
            put(
                "compat",
                buildJsonObject {
                    put("supportsDeveloperRole", JsonPrimitive(false))
                    put("supportsReasoningEffort", JsonPrimitive(false))
                },
            )
            put(
                "models",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", modelId)
                            put("name", modelId)
                            put("input", buildJsonArray { add("text") })
                        },
                    )
                },
            )
        }
        val providers = mergePiProviders(
            userFile = File(home, ".pi/agent/models.json"),
            localProviderId = providerId,
            localProvider = localProvider,
        )
        val body = buildJsonObject { put("providers", providers) }
        File(agentDir, "models.json").writeText(json.encodeToString(JsonObject.serializer(), body) + "\n")
        linkUserPiAgentFiles(File(home, ".pi/agent"), agentDir)
        writePiSettings(File(home, ".pi/agent/settings.json"), agentDir, providerId, modelId)
        return agentDir
    }

    private fun mergePiProviders(
        userFile: File,
        localProviderId: String,
        localProvider: JsonObject,
    ): JsonObject {
        val userProviders = if (userFile.isFile) {
            runCatching { json.parseToJsonElement(userFile.readText()).jsonObject["providers"]?.jsonObject }
                .getOrNull()
        } else {
            null
        }
        return buildJsonObject {
            userProviders?.forEach { (key, value) ->
                if (key != localProviderId) put(key, value)
            }
            put(localProviderId, localProvider)
        }
    }

    /**
     * Pin the overlay session to the local backend. `pi-acp` 0.0.33 does not forward
     * `--provider`/`--model`, and a symlink to `~/.pi/agent/settings.json` keeps the
     * user's cloud default (e.g. openai-codex).
     */
    private fun writePiSettings(userFile: File, overlay: File, providerId: String, modelId: String) {
        val dest = File(overlay, "settings.json")
        runCatching { Files.deleteIfExists(dest.toPath()) }
        val user = if (userFile.isFile) {
            runCatching { json.parseToJsonElement(userFile.readText()).jsonObject }.getOrNull()
        } else {
            null
        }
        val body = buildJsonObject {
            user?.forEach { (key, value) ->
                if (key != "defaultProvider" && key != "defaultModel") put(key, value)
            }
            put("defaultProvider", JsonPrimitive(providerId))
            put("defaultModel", JsonPrimitive(modelId))
        }
        dest.writeText(json.encodeToString(JsonObject.serializer(), body) + "\n")
    }

    /** Expose user skills without rewriting `~/.pi/agent/models.json` or settings. */
    private fun linkUserPiAgentFiles(userAgent: File, overlay: File) {
        if (!userAgent.isDirectory) return
        val skip = setOf("models.json", "sessions", "pi-debug.log", "settings.json")
        userAgent.listFiles()?.forEach { source ->
            if (source.name in skip) return@forEach
            val dest = File(overlay, source.name)
            if (dest.exists()) return@forEach
            runCatching { Files.createSymbolicLink(dest.toPath(), source.toPath()) }
        }
    }
}
