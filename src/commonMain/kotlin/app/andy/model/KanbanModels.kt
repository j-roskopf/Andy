package app.andy.model

import kotlinx.serialization.Serializable

@Serializable
data class KanbanBoard(
    val lanes: List<KanbanLane> = defaultKanbanLanes(),
)

@Serializable
data class KanbanLane(
    val id: String,
    val name: String,
    val cards: List<KanbanCard> = emptyList(),
)

@Serializable
data class KanbanCard(
    val id: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

fun defaultKanbanLanes(): List<KanbanLane> = listOf(
    KanbanLane(id = "todo", name = "To-Do"),
    KanbanLane(id = "doing", name = "Doing"),
    KanbanLane(id = "done", name = "Done"),
)
