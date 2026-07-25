package app.andy.desktop.service

import app.andy.model.AgentAutonomy
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentTaskDraft
import app.andy.service.AgentRunService
import app.andy.service.ProjectWorkflowService
import app.andy.terminal.TmuxAndy
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val agentToolsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Registers agent/project/chat MCP tools that delegate to [AgentRunService] /
 * [ProjectWorkflowService].
 */
fun Server.registerAgentProjectTools(
    agentRuns: AgentRunService,
    projectWorkflows: ProjectWorkflowService,
) {
    fun register(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        handler: suspend (Map<String, JsonElement>) -> CallToolResult,
    ) {
        val propertiesObject = buildJsonObject {
            properties.forEach { (k, v) -> put(k, v) }
        }
        addTool(
            name,
            description,
            ToolSchema(
                properties = propertiesObject,
                required = required.takeIf { it.isNotEmpty() },
            ),
        ) { request ->
            try {
                handler(request.arguments ?: emptyMap())
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message ?: e.toString()}")),
                    isError = true,
                )
            }
        }
    }

    fun str(args: Map<String, JsonElement>, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    fun textResult(value: String) =
        CallToolResult(content = listOf(TextContent(text = value)))

    register(
        name = "chat.list",
        description = "List Andy agent chats/tasks with status and metadata",
    ) {
        val tasks = agentRuns.tasks.value
        val arr = buildJsonArray {
            tasks.forEach { task ->
                add(
                    buildJsonObject {
                        put("id", task.id)
                        put("title", task.title)
                        put("agent", task.agent.name)
                        put("status", task.status.name)
                        put("projectId", task.projectId.orEmpty())
                        put("cwd", task.cwd.orEmpty())
                        put("unread", task.unread)
                        put("archived", task.archived)
                        put("createdAtMillis", task.createdAtMillis)
                        put("tmuxSession", TmuxAndy.sessionName(task.id))
                        put("tmuxAlive", TmuxAndy.isAvailable() && TmuxAndy.hasSession(task.id))
                    },
                )
            }
        }
        textResult(arr.toString())
    }

    register(
        name = "chat.composer_options",
        description = "Catalog for starting a chat: providers, models, autonomy, projects",
    ) {
        // Use cached CLI/model probes — do not refresh here (that blocks the CLI for seconds).
        val statuses = agentRuns.cliStatuses.value.associateBy { it.kind }
        val discovered = agentRuns.providerModels.value
        val agents = buildJsonArray {
            AgentKind.entries.forEach { kind ->
                val status = statuses[kind]
                add(
                    buildJsonObject {
                        put("id", kind.name)
                        put("label", kind.label)
                        put("cliName", kind.cliName)
                        put("ready", status?.ready ?: false)
                        put("available", status?.available ?: false)
                        put("version", status?.version.orEmpty())
                        put("issue", status?.issue?.title.orEmpty())
                    },
                )
            }
        }
        val models = buildJsonObject {
            AgentKind.entries.forEach { kind ->
                val options = discovered[kind].orEmpty().ifEmpty { AgentModelCatalog.options(kind) }
                put(
                    kind.name,
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "")
                                put("label", "provider default")
                            },
                        )
                        options.forEach { opt ->
                            add(
                                buildJsonObject {
                                    put("id", opt.id)
                                    put("label", opt.label)
                                },
                            )
                        }
                    },
                )
            }
        }
        val autonomies = buildJsonArray {
            AgentAutonomy.entries.forEach { autonomy ->
                add(
                    buildJsonObject {
                        put("id", autonomy.name)
                        put("label", autonomy.label)
                    },
                )
            }
        }
        val projects = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "")
                    put("label", "Inbox (no project)")
                    put("directory", "")
                },
            )
            for (id in projectWorkflows.projects.value.keys.sorted()) {
                val directory = projectWorkflows.projectContextDir(id).orEmpty()
                add(
                    buildJsonObject {
                        put("id", id)
                        put("label", id)
                        put("directory", directory)
                    },
                )
            }
        }
        textResult(
            buildJsonObject {
                put("agents", agents)
                put("models", models)
                put("autonomies", autonomies)
                put("projects", projects)
            }.toString(),
        )
    }

    register(
        name = "chat.start",
        description = "Start a new agent chat/task",
        properties = mapOf(
            "prompt" to buildJsonObject {
                put("type", "string")
                put("description", "Initial prompt for the agent")
            },
            "agent" to buildJsonObject {
                put("type", "string")
                put("description", "ClaudeCode | Codex | Cursor | Antigravity")
            },
            "title" to buildJsonObject {
                put("type", "string")
                put("description", "Optional title")
            },
            "projectId" to buildJsonObject {
                put("type", "string")
                put("description", "Optional project id")
            },
            "directory" to buildJsonObject {
                put("type", "string")
                put("description", "Working directory")
            },
            "model" to buildJsonObject {
                put("type", "string")
                put("description", "Optional model id (empty = provider default)")
            },
            "autonomy" to buildJsonObject {
                put("type", "string")
                put("description", "ReadOnly | Standard | Full")
            },
        ),
        required = listOf("prompt", "agent"),
    ) { args ->
        val prompt = str(args, "prompt") ?: error("prompt required")
        val agentName = str(args, "agent") ?: error("agent required")
        val agent = AgentKind.entries.firstOrNull {
            it.name.equals(agentName, ignoreCase = true) ||
                it.cliName.equals(agentName, ignoreCase = true)
        } ?: error("unknown agent: $agentName")
        val autonomyName = str(args, "autonomy")
        val autonomy = autonomyName?.let { name ->
            AgentAutonomy.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        } ?: AgentAutonomy.Standard
        val model = str(args, "model")?.takeIf { it.isNotBlank() }
        val task = agentRuns.createAndStart(
            AgentTaskDraft(
                title = str(args, "title")?.takeIf { it.isNotBlank() } ?: prompt.take(48),
                prompt = prompt,
                agent = agent,
                projectId = str(args, "projectId")?.takeIf { it.isNotBlank() },
                directory = str(args, "directory")?.takeIf { it.isNotBlank() },
                autonomy = autonomy,
                model = model,
            ),
        )
        textResult(
            buildJsonObject {
                put("id", task.id)
                put("status", task.status.name)
                put("tmuxSession", TmuxAndy.sessionName(task.id))
                put("attach", "tmux -L andy attach -t ${TmuxAndy.sessionName(task.id)}")
            }.toString(),
        )
    }

    register(
        name = "chat.stop",
        description = "Stop a running agent chat",
        properties = mapOf(
            "taskId" to buildJsonObject {
                put("type", "string")
                put("description", "Task id")
            },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        agentRuns.stop(id)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.resume",
        description = "Send a follow-up message to an agent chat (reattaches if needed)",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "followUp" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId", "followUp"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val followUp = str(args, "followUp") ?: error("followUp required")
        agentRuns.resume(id, followUp)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.respond",
        description = "Respond to a waiting user-input request on an agent chat",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "requestId" to buildJsonObject { put("type", "string") },
            "answers" to buildJsonObject {
                put("type", "object")
                put("description", "Map of questionId -> answer")
            },
        ),
        required = listOf("taskId", "requestId", "answers"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val requestId = str(args, "requestId") ?: error("requestId required")
        val answersObj = args["answers"] as? JsonObject ?: error("answers must be object")
        val answers = answersObj.mapValues { it.value.jsonPrimitive.content }
        agentRuns.respondToUserInput(id, requestId, answers)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.status",
        description = "Get live session status for a chat",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val status = agentRuns.sessionStatus(id).value
        val task = agentRuns.tasks.value.firstOrNull { it.id == id }
        textResult(
            buildJsonObject {
                put("id", id)
                put("taskStatus", task?.status?.name)
                put("sessionStatus", status?.name)
                put("tmuxAlive", TmuxAndy.isAvailable() && TmuxAndy.hasSession(id))
                put("tmuxSession", TmuxAndy.sessionName(id))
            }.toString(),
        )
    }

    register(
        name = "chat.attach_command",
        description = "Return the tmux attach command for a chat's live terminal",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        textResult(
            buildJsonObject {
                put("command", "tmux -L andy attach -t ${TmuxAndy.sessionName(id)}")
                val binary = if (TmuxAndy.isAvailable()) TmuxAndy.tmuxBinary() else "tmux"
                put("argv", buildJsonArray {
                    add(JsonPrimitive(binary))
                    add(JsonPrimitive("-L"))
                    add(JsonPrimitive("andy"))
                    add(JsonPrimitive("attach-session"))
                    add(JsonPrimitive("-t"))
                    add(JsonPrimitive(TmuxAndy.sessionName(id)))
                })
            }.toString(),
        )
    }

    register(
        name = "chat.reattach",
        description = "Quietly reopen an ended chat's provider CLI session (no follow-up). " +
            "No-op if the tmux session is already alive. Use chat.resume when reattach is impossible.",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val tmuxAlive = TmuxAndy.isAvailable() && TmuxAndy.hasSession(id)
        if (tmuxAlive) {
            textResult(
                buildJsonObject {
                    put("ok", true)
                    put("id", id)
                    put("tmuxAlive", true)
                    put("reattached", false)
                }.toString(),
            )
        } else if (agentRuns.canReattachSession(id)) {
            agentRuns.reattachSession(id)
            textResult(
                buildJsonObject {
                    put("ok", true)
                    put("id", id)
                    put("tmuxAlive", TmuxAndy.isAvailable() && TmuxAndy.hasSession(id))
                    put("reattached", true)
                }.toString(),
            )
        } else {
            textResult(
                buildJsonObject {
                    put("ok", false)
                    put("id", id)
                    put("tmuxAlive", false)
                    put("reattached", false)
                    put(
                        "error",
                        "cannot reattach (missing vendor session); use chat.resume",
                    )
                }.toString(),
            )
        }
    }

    register(
        name = "project.list",
        description = "List project workflow states",
    ) {
        val projects = projectWorkflows.projects.value
        val arr = buildJsonArray {
            projects.forEach { (id, state) ->
                add(
                    buildJsonObject {
                        put("projectId", id)
                        put("taskCount", state.tasks.size)
                        put("scratchpadLen", state.scratchpad.length)
                    },
                )
            }
        }
        textResult(arr.toString())
    }

    register(
        name = "workflow.run_spec",
        description = "Run a project spec workflow task",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "revisionRequest" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        projectWorkflows.runSpec(id, str(args, "revisionRequest"))
        textResult("""{"ok":true,"taskId":"$id"}""")
    }

    register(
        name = "workflow.start_build",
        description = "Start a project build-pair workflow",
        properties = mapOf(
            "buildTaskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("buildTaskId"),
    ) { args ->
        val id = str(args, "buildTaskId") ?: error("buildTaskId required")
        projectWorkflows.startBuildPair(id)
        textResult("""{"ok":true,"buildTaskId":"$id"}""")
    }
}

/** Tool names added by [registerAgentProjectTools]. */
fun agentProjectToolNames(): List<String> = listOf(
    "chat.list",
    "chat.composer_options",
    "chat.start",
    "chat.stop",
    "chat.resume",
    "chat.respond",
    "chat.status",
    "chat.attach_command",
    "chat.reattach",
    "project.list",
    "workflow.run_spec",
    "workflow.start_build",
)
