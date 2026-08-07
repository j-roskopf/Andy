package app.andy.desktop.service

import app.andy.desktop.service.agents.AgentWorkflowArtifacts
import app.andy.desktop.service.agents.DesktopAgentRunService
import app.andy.desktop.service.agents.appendAgentStatus
import app.andy.model.AgentStatus
import app.andy.model.AgentAutonomy
import app.andy.model.AgentContextualProvenance
import app.andy.model.AgentEvent
import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentTaskDraft
import app.andy.model.AgentUserInputRequest
import app.andy.model.ContextualActionKind
import app.andy.model.ProjectSpecDraft
import app.andy.service.AgentRunService
import app.andy.service.ProjectWorkflowService
import app.andy.terminal.TmuxAndy
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** MCP argument keys that would smuggle a raw filesystem path in place of a managed evidence bundle id. */
private val DisallowedRawPathKeys = listOf("evidencePath", "evidencePaths", "filePath", "filePaths", "localPath")

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

    fun strList(args: Map<String, JsonElement>, key: String): List<String> =
        (args[key] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

    fun textResult(value: String) =
        CallToolResult(content = listOf(TextContent(text = value)))

    fun AgentEvent.toWire(): JsonObject = buildJsonObject {
        put("atMillis", atMillis)
        when (this@toWire) {
            is AgentEvent.SessionStarted -> { put("type", "session"); put("sessionId", sessionId.orEmpty()); put("model", model.orEmpty()) }
            is AgentEvent.AssistantText -> { put("type", "assistant"); put("text", text); put("stream", isStreamDelta) }
            is AgentEvent.Thinking -> { put("type", "thinking"); put("text", text); put("stream", isStreamDelta) }
            is AgentEvent.UserMessage -> { put("type", "user"); put("text", text); put("images", JsonArray(imagePaths.map(::JsonPrimitive))) }
            is AgentEvent.ToolCall -> {
                put("type", "tool")
                put("toolName", toolName)
                put("toolCallId", toolCallId.orEmpty())
                put("summary", summary)
                put("detail", detail)
                put("kind", kind?.name.orEmpty())
                put("state", state.name)
                put("locations", JsonArray(locations.map(::JsonPrimitive)))
            }
            is AgentEvent.ToolResult -> {
                put("type", "tool-result"); put("toolName", toolName.orEmpty()); put("summary", summary)
                put("detail", detail); put("isError", isError)
            }
            is AgentEvent.TaskError -> { put("type", "error"); put("text", message) }
            is AgentEvent.TaskResult -> {
                put("type", "result"); put("success", success); put("finalText", finalText.orEmpty())
                put("costUsd", costUsd ?: 0.0); put("costEstimated", costIsEstimated)
                put("inputTokens", inputTokens ?: 0L); put("outputTokens", outputTokens ?: 0L)
                put("durationMs", durationMs ?: 0L)
            }
            is AgentEvent.ContextUsage -> { put("type", "usage"); put("usedTokens", usedTokens ?: 0L); put("windowTokens", windowTokens ?: 0L) }
            is AgentEvent.PlanUpdate -> {
                put("type", "plan")
                put("entries", buildJsonArray { entries.forEach { entry -> add(buildJsonObject { put("content", entry.content); put("status", entry.status) }) } })
                markdown?.let { put("markdown", it) }
            }
            is AgentEvent.ModeChanged -> { put("type", "mode"); put("modeId", modeId) }
            is AgentEvent.AvailableCommands -> {
                put("type", "commands")
                put("commands", buildJsonArray { commands.forEach { command -> add(buildJsonObject { put("name", command.name); put("description", command.description); put("inputHint", command.inputHint.orEmpty()) }) } })
            }
            is AgentEvent.AvailableModes -> {
                put("type", "modes")
                put("currentModeId", currentModeId.orEmpty())
                put("modes", buildJsonArray { modes.forEach { mode -> add(buildJsonObject { put("id", mode.id); put("name", mode.name); put("description", mode.description.orEmpty()) }) } })
            }
            is AgentEvent.PermissionRequest -> {
                put("type", "permission"); put("requestId", requestId); put("toolName", toolName); put("question", question)
                put("options", buildJsonArray { options.forEach { option -> add(buildJsonObject { put("label", option.label); put("description", option.description) }) } })
            }
            is AgentEvent.PermissionResolved -> {
                put("type", "permission-resolved")
                put("requestId", requestId)
                put("optionId", optionId)
                put("allowed", allowed)
                note?.let { put("note", it) }
            }
            is AgentEvent.Raw -> { put("type", "raw"); put("line", line) }
        }
    }

    /**
     * Managed evidence bundle ids are the only way to attach investigation context over MCP
     * (§4/§5) — reject any argument that looks like it is trying to smuggle a raw filesystem
     * path instead, with a clear error pointing the caller at `contextBundleIds`.
     */
    fun rejectRawEvidencePaths(args: Map<String, JsonElement>) {
        val found = DisallowedRawPathKeys.firstOrNull { it in args }
        if (found != null) {
            error(
                "'$found' is not accepted — Andy MCP tools only accept managed evidence bundle ids " +
                    "(contextBundleIds), never raw filesystem paths",
            )
        }
    }

    fun parseProvenance(args: Map<String, JsonElement>): AgentContextualProvenance? {
        val obj = args["provenance"] as? JsonObject ?: return null
        val sourceKindName = obj["sourceKind"]?.jsonPrimitive?.contentOrNull ?: return null
        val sourceKind = ContextualActionKind.entries.firstOrNull { it.name.equals(sourceKindName, ignoreCase = true) }
            ?: return null
        fun field(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return AgentContextualProvenance(
            sourceKind = sourceKind,
            investigationId = field("investigationId"),
            eventId = field("eventId"),
            playbackMillis = obj["playbackMillis"]?.jsonPrimitive?.longOrNull,
            networkExchangeId = field("networkExchangeId"),
            crashId = field("crashId"),
            hierarchyNodeId = field("hierarchyNodeId"),
            packageName = field("packageName"),
        )
    }

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
                        put("prompt", task.prompt)
                        put("agent", task.agent.name)
                        put("lane", task.lane.name)
                        put("status", task.status?.name.orEmpty())
                        put("projectId", task.projectId.orEmpty())
                        put("cwd", task.cwd.orEmpty())
                        put("originDir", task.originDir.orEmpty())
                        put("useWorktree", task.useWorktree)
                        put("worktreePath", task.worktreePath.orEmpty())
                        put("branchName", task.branchName.orEmpty())
                        put("ownsWorktree", task.ownsWorktree)
                        put("parentWorktreeTaskId", task.parentWorktreeTaskId.orEmpty())
                        put("unread", task.unread)
                        put("archived", task.archived)
                        put("transcriptCompressed", task.transcriptCompressed)
                        put("createdAtMillis", task.createdAtMillis)
                        put("startedAtMillis", task.startedAtMillis ?: 0L)
                        put("finishedAtMillis", task.finishedAtMillis ?: 0L)
                        put("resumable", task.resumable)
                        put("interrupted", task.interrupted)
                        put("stoppedByUser", task.stoppedByUser)
                        put("statusConfident", task.statusConfident)
                        put("exitCode", task.exitCode ?: Int.MIN_VALUE)
                        put("vendorSessionId", task.vendorSessionId.orEmpty())
                        put("acpSessionId", task.acpSessionId.orEmpty())
                        put("stopReason", task.stopReason.orEmpty())
                        put("errorMessage", task.errorMessage.orEmpty())
                        task.userInputRequest?.let { request ->
                            put("userInputRequest", buildJsonObject {
                                put("id", request.id)
                                put("origin", request.origin.name)
                                put("questions", buildJsonArray {
                                    request.questions.forEach { question ->
                                        add(buildJsonObject {
                                            put("id", question.id)
                                            put("header", question.header)
                                            put("question", question.question)
                                            put("options", buildJsonArray { question.options.forEach { option -> add(buildJsonObject { put("label", option.label); put("description", option.description) }) } })
                                        })
                                    }
                                })
                            })
                        }
                        put("planMode", task.planMode)
                        if (task.queuedFollowUps.isNotEmpty()) {
                            put(
                                "queuedFollowUps",
                                buildJsonArray {
                                    task.queuedFollowUps.forEach { queued ->
                                        add(
                                            buildJsonObject {
                                                put("text", queued.text)
                                                put("contextBundleIds", buildJsonArray {
                                                    queued.contextBundleIds.forEach { id -> add(JsonPrimitive(id)) }
                                                })
                                            },
                                        )
                                    }
                                },
                            )
                        }
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
                        put("acpReady", status?.acpReady ?: false)
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
        name = "chat.events",
        description = "Read the structured transcript for an ACP chat",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val events = agentRuns.events(id).value
        textResult(buildJsonArray { events.forEach { add(it.toWire()) } }.toString())
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
            "useWorktree" to buildJsonObject {
                put("type", "boolean")
                put("description", "Create an isolated git worktree for this chat")
            },
            "baseWorktreeTaskId" to buildJsonObject {
                put("type", "string")
                put("description", "Optional Andy task id whose worktree branch to fork from")
            },
            "model" to buildJsonObject {
                put("type", "string")
                put("description", "Optional model id (empty = provider default)")
            },
            "autonomy" to buildJsonObject {
                put("type", "string")
                put("description", "ReadOnly | Standard | Full")
            },
            "contextBundleIds" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Managed evidence bundle ids (§4) to attach; never raw filesystem paths")
            },
            "provenance" to buildJsonObject {
                put("type", "object")
                put(
                    "description",
                    "Where this contextual action originated: {sourceKind, investigationId?, eventId?, " +
                        "playbackMillis?, networkExchangeId?, crashId?, hierarchyNodeId?, packageName?}",
                )
            },
        ),
        required = listOf("prompt", "agent"),
    ) { args ->
        rejectRawEvidencePaths(args)
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
        val useWorktree = args["useWorktree"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: args["useWorktree"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val task = agentRuns.createAndStart(
            AgentTaskDraft(
                title = str(args, "title")?.takeIf { it.isNotBlank() } ?: prompt.take(48),
                prompt = prompt,
                agent = agent,
                projectId = str(args, "projectId")?.takeIf { it.isNotBlank() },
                directory = str(args, "directory")?.takeIf { it.isNotBlank() },
                useWorktree = useWorktree,
                baseWorktreeTaskId = str(args, "baseWorktreeTaskId")?.takeIf { it.isNotBlank() },
                autonomy = autonomy,
                model = model,
                contextBundleIds = strList(args, "contextBundleIds"),
                provenance = parseProvenance(args),
            ),
        )
        textResult(
            buildJsonObject {
                put("id", task.id)
                put("status", task.status?.name.orEmpty())
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
        name = "chat.mark_read",
        description = "Clear the unread badge for a chat (e.g. when opened)",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        agentRuns.markRead(id)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.set_viewing",
        description = "Track which chat the user is viewing so unread badges and status attention are suppressed",
        properties = mapOf(
            "taskId" to buildJsonObject {
                put("type", "string")
                put("description", "Omit to clear all viewing state")
            },
            "viewing" to buildJsonObject { put("type", "boolean") },
        ),
        required = listOf("viewing"),
    ) { args ->
        val viewing = args["viewing"]?.jsonPrimitive?.booleanOrNull ?: error("viewing required")
        val id = str(args, "taskId")
        agentRuns.setChatViewing(id, viewing)
        textResult("""{"ok":true,"viewing":$viewing,"id":"${id.orEmpty()}"}""")
    }

    register(
        name = "chat.set_app_focus",
        description = "Track whether the Andy window is foreground; a chat open behind another " +
            "app still earns unread badges and notifications",
        properties = mapOf(
            "focused" to buildJsonObject { put("type", "boolean") },
        ),
        required = listOf("focused"),
    ) { args ->
        val focused = args["focused"]?.jsonPrimitive?.booleanOrNull ?: error("focused required")
        agentRuns.setAppForeground(focused)
        textResult("""{"ok":true,"focused":$focused}""")
    }

    register(
        name = "chat.mark_unread",
        description = "Mark a chat unread so list/dock badges show again",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        agentRuns.markUnread(id)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.archive",
        description = "Hide a finished chat from the default list without deleting it",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        agentRuns.archive(id)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.unarchive",
        description = "Restore an archived chat to the default list",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        agentRuns.unarchive(id)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.reconcile",
        description = "Repair a chat left in a contradictory Working/finished state after a crash or stale scrape",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        (agentRuns as? DesktopAgentRunService)?.reconcileStaleActiveTaskIfNeeded(id)
        val task = agentRuns.tasks.value.firstOrNull { it.id == id }
        textResult(
            buildJsonObject {
                put("ok", true)
                put("id", id)
                put("status", task?.status?.name.orEmpty())
                put("unread", task?.unread ?: false)
                put("finishedAtMillis", task?.finishedAtMillis ?: 0L)
            }.toString(),
        )
    }

    register(
        name = "chat.delete",
        description = "Delete an agent chat/task and its artifacts",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "removeWorktree" to buildJsonObject {
                put("type", "boolean")
                put("description", "Also remove an owned git worktree")
            },
            "force" to buildJsonObject {
                put("type", "boolean")
                put("description", "Delete even when child worktrees still reference this task")
            },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val removeWorktree = args["removeWorktree"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: args["removeWorktree"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val force = args["force"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: args["force"]?.jsonPrimitive?.booleanOrNull
            ?: false
        when (val outcome = agentRuns.delete(id, removeWorktree, force)) {
            is app.andy.model.WorktreeDeleteOutcome.BlockedByChildren -> {
                textResult(
                    buildJsonObject {
                        put("ok", false)
                        put("blockedByChildren", true)
                        put("id", id)
                        put(
                            "children",
                            buildJsonArray {
                                outcome.children.forEach { child ->
                                    add(
                                        buildJsonObject {
                                            put("taskId", child.taskId)
                                            put("title", child.title)
                                            put("branch", child.branch)
                                            put("path", child.path)
                                        },
                                    )
                                }
                            },
                        )
                    }.toString(),
                )
            }
            app.andy.model.WorktreeDeleteOutcome.Deleted -> textResult("""{"ok":true,"id":"$id"}""")
        }
    }

    register(
        name = "chat.resume",
        description = "Send a follow-up message to an agent chat (reattaches if needed)",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "followUp" to buildJsonObject { put("type", "string") },
            "contextBundleIds" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Managed evidence bundle ids (§4) to attach; never raw filesystem paths")
            },
        ),
        required = listOf("taskId", "followUp"),
    ) { args ->
        rejectRawEvidencePaths(args)
        val id = str(args, "taskId") ?: error("taskId required")
        val followUp = str(args, "followUp") ?: error("followUp required")
        agentRuns.resume(id, followUp, contextBundleIds = strList(args, "contextBundleIds"))
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.queue_follow_up",
        description = "Queue a follow-up message for an agent chat instead of sending immediately",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "followUp" to buildJsonObject { put("type", "string") },
            "contextBundleIds" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "Managed evidence bundle ids (§4) to attach; never raw filesystem paths")
            },
        ),
        required = listOf("taskId", "followUp"),
    ) { args ->
        rejectRawEvidencePaths(args)
        val id = str(args, "taskId") ?: error("taskId required")
        val followUp = str(args, "followUp") ?: error("followUp required")
        agentRuns.queueFollowUp(id, followUp, contextBundleIds = strList(args, "contextBundleIds"))
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.remove_queued_follow_up",
        description = "Remove a queued follow-up at the given index",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "queueIndex" to buildJsonObject { put("type", "integer") },
        ),
        required = listOf("taskId", "queueIndex"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val queueIndex = args["queueIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: error("queueIndex required")
        agentRuns.removeQueuedFollowUp(id, queueIndex)
        textResult("""{"ok":true,"id":"$id","queueIndex":$queueIndex}""")
    }

    register(
        name = "chat.send_next_queued_follow_up",
        description = "Send the next queued follow-up when the chat is idle",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        agentRuns.sendNextQueuedFollowUp(id)
        textResult("""{"ok":true,"id":"$id"}""")
    }

    register(
        name = "chat.update_plan_mode",
        description = "Toggle Andy plan mode for follow-ups and sync the live ACP session when supported",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "planMode" to buildJsonObject { put("type", "boolean") },
        ),
        required = listOf("taskId", "planMode"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val planMode = args["planMode"]?.jsonPrimitive?.booleanOrNull ?: error("planMode required")
        agentRuns.updatePlanMode(id, planMode)
        textResult("""{"ok":true,"id":"$id","planMode":$planMode}""")
    }

    register(
        name = "chat.set_mode",
        description = "Switch the live ACP session mode for providers that advertise modes",
        properties = mapOf(
            "taskId" to buildJsonObject { put("type", "string") },
            "modeId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("taskId", "modeId"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val modeId = str(args, "modeId") ?: error("modeId required")
        agentRuns.setAcpSessionMode(id, modeId)
        textResult("""{"ok":true,"id":"$id","modeId":"$modeId"}""")
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
        val task = agentRuns.tasks.value.firstOrNull { it.id == id }
        textResult(
            buildJsonObject {
                put("id", id)
                put("status", task?.status?.name.orEmpty())
                put("statusConfident", task?.statusConfident ?: false)
                put("tmuxAlive", TmuxAndy.isAvailable() && TmuxAndy.hasSession(id))
                put("tmuxSession", TmuxAndy.sessionName(id))
            }.toString(),
        )
    }

    register(
        name = "agent_status",
        description = "Optional: report agent turn status to Andy (writes .andy/<taskId>/status.json). Andy already infers working/idle from terminal output.",
        properties = mapOf(
            "taskId" to buildJsonObject {
                put("type", "string")
                put("description", "Andy chat/task id")
            },
            "status" to buildJsonObject {
                put("type", "string")
                put("description", "working | blocked | done | error")
            },
        ),
        required = listOf("taskId", "status"),
    ) { args ->
        val id = str(args, "taskId") ?: error("taskId required")
        val statusRaw = str(args, "status")?.trim()?.lowercase() ?: error("status required")
        val status = when (statusRaw) {
            "working" -> AgentStatus.Working
            "blocked" -> AgentStatus.Blocked
            "done" -> AgentStatus.Done
            "error", "failed" -> AgentStatus.Error
            else -> error("status must be working, blocked, done, or error")
        }
        val task = agentRuns.tasks.value.firstOrNull { it.id == id } ?: error("unknown task: $id")
        val artifactDir = AgentWorkflowArtifacts.dirFor(task.cwd?.let(::File), id)
        appendAgentStatus(artifactDir, status)
        textResult(
            buildJsonObject {
                put("ok", true)
                put("taskId", id)
                put("status", status.name.lowercase())
                put("artifactDir", artifactDir.absolutePath)
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
        // Alive + not broken is enough. Requiring isActive/isTerminalLive used a 1s
        // session-list cache that could miss a Done-but-still-open pane and kill it.
        val healthySession = tmuxAlive && !TmuxAndy.sessionLooksBroken(id)
        if (healthySession) {
            textResult(
                buildJsonObject {
                    put("ok", true)
                    put("id", id)
                    put("tmuxAlive", true)
                    put("reattached", false)
                }.toString(),
            )
        } else if (tmuxAlive) {
            TmuxAndy.killSession(id)
            agentRuns.reattachSession(id)
            val appeared = TmuxAndy.waitForSession(id, timeoutMs = 30_000)
            textResult(
                buildJsonObject {
                    put("ok", appeared)
                    put("id", id)
                    put("tmuxAlive", appeared)
                    put("reattached", true)
                    if (!appeared) {
                        put("error", "reattach started but tmux session did not appear")
                    }
                }.toString(),
            )
        } else if (agentRuns.canReattachSession(id)) {
            agentRuns.reattachSession(id)
            val appeared = TmuxAndy.waitForSession(id, timeoutMs = 30_000)
            textResult(
                buildJsonObject {
                    put("ok", appeared)
                    put("id", id)
                    put("tmuxAlive", appeared)
                    put("reattached", true)
                    if (!appeared) {
                        put("error", "reattach started but tmux session did not appear")
                    }
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
        name = "workflow.save_spec",
        description = "Create or update a project spec draft",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "title" to buildJsonObject { put("type", "string") },
            "brief" to buildJsonObject { put("type", "string") },
            "taskId" to buildJsonObject { put("type", "string") },
            "agent" to buildJsonObject { put("type", "string") },
            "model" to buildJsonObject { put("type", "string") },
            "includeScratchpad" to buildJsonObject { put("type", "boolean") },
            "grillMeEnabled" to buildJsonObject { put("type", "boolean") },
        ),
        required = listOf("projectId", "title", "brief"),
    ) { args ->
        val projectId = str(args, "projectId") ?: error("projectId required")
        val title = str(args, "title") ?: error("title required")
        val brief = str(args, "brief") ?: error("brief required")
        val agentName = str(args, "agent")
        val agent = agentName?.let { name ->
            AgentKind.entries.firstOrNull {
                it.name.equals(name, ignoreCase = true) ||
                    it.cliName.equals(name, ignoreCase = true)
            }
        } ?: AgentKind.Codex
        val id = projectWorkflows.saveSpec(
            ProjectSpecDraft(
                projectId = projectId,
                title = title,
                brief = brief,
                profile = app.andy.model.ProjectAgentProfile(
                    agent = agent,
                    model = str(args, "model")?.takeIf { it.isNotBlank() },
                ),
                includeScratchpad = args["includeScratchpad"]?.jsonPrimitive?.booleanOrNull
                    ?: args["includeScratchpad"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: false,
                grillMeEnabled = args["grillMeEnabled"]?.jsonPrimitive?.booleanOrNull
                    ?: args["grillMeEnabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    ?: false,
                taskId = str(args, "taskId")?.takeIf { it.isNotBlank() },
            ),
        )
        textResult("""{"ok":true,"taskId":"$id"}""")
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
    "chat.mark_read",
    "chat.set_viewing",
    "chat.set_app_focus",
    "chat.mark_unread",
    "chat.archive",
    "chat.unarchive",
    "chat.reconcile",
    "chat.delete",
    "chat.resume",
    "chat.queue_follow_up",
    "chat.remove_queued_follow_up",
    "chat.send_next_queued_follow_up",
    "chat.update_plan_mode",
    "chat.set_mode",
    "chat.respond",
    "chat.status",
    "agent_status",
    "chat.attach_command",
    "chat.reattach",
    "project.list",
    "workflow.save_spec",
    "workflow.run_spec",
    "workflow.start_build",
)
