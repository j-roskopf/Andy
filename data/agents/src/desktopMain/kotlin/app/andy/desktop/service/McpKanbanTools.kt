package app.andy.desktop.service

import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.andy.model.KanbanLaneRole
import app.andy.model.defaultKanbanLanes
import app.andy.service.KanbanLaneDirection
import app.andy.service.KanbanService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Tool names added by [registerKanbanTools]. */
fun kanbanToolNames(): List<String> = listOf(
    "kanban.board_get",
    "kanban.card_create",
    "kanban.card_update",
    "kanban.card_move",
    "kanban.card_delete",
    "kanban.card_link_chat",
    "kanban.card_set_pinned",
    "kanban.lane_create",
    "kanban.lane_rename",
    "kanban.lane_delete",
    "kanban.lane_move",
    "kanban.lane_set_role",
)

fun Server.registerKanbanTools(kanban: KanbanService) {
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
                required = required,
            ),
        ) { request ->
            try {
                val args = request.arguments ?: emptyMap()
                handler(args)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent(text = "Error: ${e.message ?: e.toString()}")),
                    isError = true,
                )
            }
        }
    }

    fun textResult(json: JsonObject) = CallToolResult(
        content = listOf(TextContent(text = json.toString())),
    )

    fun str(args: Map<String, JsonElement>, key: String): String? =
        args[key]?.jsonPrimitive?.contentOrNull

    fun bool(args: Map<String, JsonElement>, key: String): Boolean? =
        args[key]?.jsonPrimitive?.booleanOrNull
            ?: args[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    fun int(args: Map<String, JsonElement>, key: String): Int? =
        args[key]?.jsonPrimitive?.intOrNull
            ?: args[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    fun tags(args: Map<String, JsonElement>): List<String> {
        val el = args["tags"] ?: return emptyList()
        return when (el) {
            is JsonArray -> el.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { t -> t.isNotEmpty() } }
            else -> str(args, "tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        }
    }

    fun requireProjectId(args: Map<String, JsonElement>): String =
        str(args, "projectId")?.takeIf { it.isNotBlank() } ?: error("projectId required")

    register(
        name = "kanban.board_get",
        description = "Get the kanban board for a project (lanes, cards, roles)",
        properties = mapOf(
            "projectId" to buildJsonObject {
                put("type", "string")
                put("description", "Project id")
            },
        ),
        required = listOf("projectId"),
    ) { args ->
        val projectId = requireProjectId(args)
        val board = kanban.boards.value[projectId] ?: KanbanBoard(lanes = defaultKanbanLanes())
        textResult(board.toJson(projectId))
    }

    register(
        name = "kanban.card_create",
        description = "Create a kanban card; returns the new card id",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "laneId" to buildJsonObject { put("type", "string") },
            "title" to buildJsonObject { put("type", "string") },
            "description" to buildJsonObject { put("type", "string") },
            "tags" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
            "parentCardId" to buildJsonObject {
                put("type", "string")
                put("description", "Optional parent card for swarm subtasks")
            },
            "swarmRunId" to buildJsonObject {
                put("type", "string")
                put("description", "Optional swarm run id grouping parent+children")
            },
        ),
        required = listOf("projectId", "laneId", "title"),
    ) { args ->
        val projectId = requireProjectId(args)
        val laneId = str(args, "laneId")?.takeIf { it.isNotBlank() } ?: error("laneId required")
        val title = str(args, "title") ?: error("title required")
        val id = kanban.addCard(
            projectId = projectId,
            laneId = laneId,
            title = title,
            description = str(args, "description").orEmpty(),
            tags = tags(args),
            parentCardId = str(args, "parentCardId")?.takeIf { it.isNotBlank() },
            swarmRunId = str(args, "swarmRunId")?.takeIf { it.isNotBlank() },
        ) ?: error("failed to create card (blank title or missing lane)")
        textResult(buildJsonObject { put("cardId", id) })
    }

    register(
        name = "kanban.card_update",
        description = "Update a kanban card's title, description, and tags",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "cardId" to buildJsonObject { put("type", "string") },
            "title" to buildJsonObject { put("type", "string") },
            "description" to buildJsonObject { put("type", "string") },
            "tags" to buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
            },
        ),
        required = listOf("projectId", "cardId", "title"),
    ) { args ->
        val projectId = requireProjectId(args)
        val cardId = str(args, "cardId")?.takeIf { it.isNotBlank() } ?: error("cardId required")
        val title = str(args, "title") ?: error("title required")
        kanban.updateCard(projectId, cardId, title, str(args, "description").orEmpty(), tags(args))
        textResult(buildJsonObject { put("ok", true) })
    }

    register(
        name = "kanban.card_move",
        description = "Move a card to a lane (pins the card so status mirroring suspends)",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "cardId" to buildJsonObject { put("type", "string") },
            "toLaneId" to buildJsonObject { put("type", "string") },
            "toIndex" to buildJsonObject {
                put("type", "integer")
                put("description", "0-based index in the target lane (default: append)")
            },
        ),
        required = listOf("projectId", "cardId", "toLaneId"),
    ) { args ->
        val projectId = requireProjectId(args)
        val cardId = str(args, "cardId")?.takeIf { it.isNotBlank() } ?: error("cardId required")
        val toLaneId = str(args, "toLaneId")?.takeIf { it.isNotBlank() } ?: error("toLaneId required")
        val toIndex = int(args, "toIndex") ?: Int.MAX_VALUE
        kanban.moveCard(projectId, cardId, toLaneId, toIndex)
        textResult(buildJsonObject { put("ok", true); put("lanePinned", true) })
    }

    register(
        name = "kanban.card_delete",
        description = "Delete a kanban card",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "cardId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("projectId", "cardId"),
    ) { args ->
        val projectId = requireProjectId(args)
        val cardId = str(args, "cardId")?.takeIf { it.isNotBlank() } ?: error("cardId required")
        kanban.deleteCard(projectId, cardId)
        textResult(buildJsonObject { put("ok", true) })
    }

    register(
        name = "kanban.card_link_chat",
        description = "Link a chat task to a kanban card (sets activeChatTaskId)",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "cardId" to buildJsonObject { put("type", "string") },
            "chatTaskId" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("projectId", "cardId", "chatTaskId"),
    ) { args ->
        val projectId = requireProjectId(args)
        val cardId = str(args, "cardId")?.takeIf { it.isNotBlank() } ?: error("cardId required")
        val chatTaskId = str(args, "chatTaskId")?.takeIf { it.isNotBlank() } ?: error("chatTaskId required")
        kanban.linkChat(projectId, cardId, chatTaskId)
        textResult(buildJsonObject { put("ok", true) })
    }

    register(
        name = "kanban.card_set_pinned",
        description = "Pin or unpin a card (pinned cards skip status mirroring)",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "cardId" to buildJsonObject { put("type", "string") },
            "pinned" to buildJsonObject { put("type", "boolean") },
        ),
        required = listOf("projectId", "cardId", "pinned"),
    ) { args ->
        val projectId = requireProjectId(args)
        val cardId = str(args, "cardId")?.takeIf { it.isNotBlank() } ?: error("cardId required")
        val pinned = bool(args, "pinned") ?: error("pinned required")
        kanban.setCardPinned(projectId, cardId, pinned)
        textResult(buildJsonObject { put("ok", true); put("lanePinned", pinned) })
    }

    register(
        name = "kanban.lane_create",
        description = "Create a kanban lane; returns the new lane id",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "name" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("projectId", "name"),
    ) { args ->
        val projectId = requireProjectId(args)
        val name = str(args, "name") ?: error("name required")
        val id = kanban.addLane(projectId, name) ?: error("failed to create lane (blank name)")
        textResult(buildJsonObject { put("laneId", id) })
    }

    register(
        name = "kanban.lane_rename",
        description = "Rename a kanban lane",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "laneId" to buildJsonObject { put("type", "string") },
            "name" to buildJsonObject { put("type", "string") },
        ),
        required = listOf("projectId", "laneId", "name"),
    ) { args ->
        val projectId = requireProjectId(args)
        val laneId = str(args, "laneId")?.takeIf { it.isNotBlank() } ?: error("laneId required")
        val name = str(args, "name") ?: error("name required")
        kanban.renameLane(projectId, laneId, name)
        textResult(buildJsonObject { put("ok", true) })
    }

    register(
        name = "kanban.lane_delete",
        description = "Delete a lane. Requires confirm:true. Refuses when the lane still holds cards.",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "laneId" to buildJsonObject { put("type", "string") },
            "confirm" to buildJsonObject {
                put("type", "boolean")
                put("description", "Must be true; lane_delete is destructive")
            },
        ),
        required = listOf("projectId", "laneId", "confirm"),
    ) { args ->
        val projectId = requireProjectId(args)
        val laneId = str(args, "laneId")?.takeIf { it.isNotBlank() } ?: error("laneId required")
        if (bool(args, "confirm") != true) {
            error("lane_delete requires confirm: true")
        }
        val board = kanban.boards.value[projectId] ?: KanbanBoard()
        val lane = board.lanes.firstOrNull { it.id == laneId }
            ?: error("lane not found: $laneId")
        // MCP is not a UI — never wipe cards the caller did not explicitly clear first.
        if (lane.cards.isNotEmpty()) {
            error("lane holds ${lane.cards.size} card(s); move or delete them first")
        }
        kanban.deleteLane(projectId, laneId)
        textResult(buildJsonObject { put("ok", true) })
    }

    register(
        name = "kanban.lane_move",
        description = "Move a lane left or right",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "laneId" to buildJsonObject { put("type", "string") },
            "direction" to buildJsonObject {
                put("type", "string")
                put("description", "Left or Right")
            },
        ),
        required = listOf("projectId", "laneId", "direction"),
    ) { args ->
        val projectId = requireProjectId(args)
        val laneId = str(args, "laneId")?.takeIf { it.isNotBlank() } ?: error("laneId required")
        val direction = when (str(args, "direction")?.trim()?.lowercase()) {
            "left" -> KanbanLaneDirection.Left
            "right" -> KanbanLaneDirection.Right
            else -> error("direction must be Left or Right")
        }
        kanban.moveLane(projectId, laneId, direction)
        textResult(buildJsonObject { put("ok", true) })
    }

    register(
        name = "kanban.lane_set_role",
        description = "Set a lane's mirror role (Todo/Doing/Blocked/Done) or clear it with null/None",
        properties = mapOf(
            "projectId" to buildJsonObject { put("type", "string") },
            "laneId" to buildJsonObject { put("type", "string") },
            "role" to buildJsonObject {
                put("type", "string")
                put("description", "Todo | Doing | Blocked | Done | None")
            },
        ),
        required = listOf("projectId", "laneId", "role"),
    ) { args ->
        val projectId = requireProjectId(args)
        val laneId = str(args, "laneId")?.takeIf { it.isNotBlank() } ?: error("laneId required")
        val roleRaw = str(args, "role")?.trim() ?: error("role required")
        val role = when {
            roleRaw.equals("none", ignoreCase = true) ||
                roleRaw.equals("null", ignoreCase = true) ||
                roleRaw.isEmpty() -> null
            else -> KanbanLaneRole.entries.firstOrNull { it.name.equals(roleRaw, ignoreCase = true) }
                ?: error("unknown role: $roleRaw (Todo|Doing|Blocked|Done|None)")
        }
        kanban.setLaneRole(projectId, laneId, role)
        textResult(buildJsonObject {
            put("ok", true)
            put("role", role?.name?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
}

private fun KanbanBoard.toJson(projectId: String): JsonObject = buildJsonObject {
    put("projectId", projectId)
    put("lanes", buildJsonArray {
        lanes.forEach { add(it.toJson()) }
    })
}

private fun KanbanLane.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("role", role?.name?.let { JsonPrimitive(it) } ?: JsonNull)
    put("cards", buildJsonArray {
        cards.forEach { add(it.toJson()) }
    })
}

private fun KanbanCard.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("description", description)
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
    put("createdAtMillis", createdAtMillis)
    put("updatedAtMillis", updatedAtMillis)
    put("linkedChatTaskIds", buildJsonArray { linkedChatTaskIds.forEach { add(JsonPrimitive(it)) } })
    put("activeChatTaskId", activeChatTaskId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("parentCardId", parentCardId?.let { JsonPrimitive(it) } ?: JsonNull)
    put("lanePinned", lanePinned)
    put("swarmRunId", swarmRunId?.let { JsonPrimitive(it) } ?: JsonNull)
}
