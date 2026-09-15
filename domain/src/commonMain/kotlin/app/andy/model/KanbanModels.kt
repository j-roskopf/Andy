package app.andy.model

import kotlinx.serialization.Serializable

@Serializable
data class KanbanBoard(
    val lanes: List<KanbanLane> = defaultKanbanLanes(),
)

@Serializable
enum class KanbanLaneRole {
    Todo,
    Doing,
    Blocked,
    Done,
}

@Serializable
data class KanbanLane(
    val id: String,
    val name: String,
    val cards: List<KanbanCard> = emptyList(),
    /** Mirror target for agent-status → lane moves. Null means the lane is not a mirror target. */
    val role: KanbanLaneRole? = null,
)

@Serializable
data class KanbanCard(
    val id: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** All chats ever assigned to this card, oldest first. Never pruned. */
    val linkedChatTaskIds: List<String> = emptyList(),
    /** The chat currently representing this card's work. */
    val activeChatTaskId: String? = null,
    /** Parent card in a swarm hierarchy. Null for top-level cards. */
    val parentCardId: String? = null,
    /**
     * When true, status mirroring skips this card (manual move pin).
     * Cleared when the owning swarm ends, or via explicit unpin.
     */
    val lanePinned: Boolean = false,
    /** Groups one swarm's cards so pin reset can target the whole run. */
    val swarmRunId: String? = null,
)

fun defaultKanbanLanes(): List<KanbanLane> = listOf(
    KanbanLane(id = "todo", name = "To-Do", role = KanbanLaneRole.Todo),
    KanbanLane(id = "doing", name = "Doing", role = KanbanLaneRole.Doing),
    KanbanLane(id = "done", name = "Done", role = KanbanLaneRole.Done),
)

/**
 * One-time role backfill for boards persisted before [KanbanLane.role] existed.
 * Uses the same done-ish heuristic the UI used for counting; does not invent a Blocked lane.
 */
fun KanbanBoard.withBackfilledLaneRoles(): KanbanBoard {
    if (lanes.all { it.role != null }) return this
    var changed = false
    val updated = lanes.map { lane ->
        if (lane.role != null) lane else {
            val role = inferredLaneRole(lane) ?: return@map lane
            changed = true
            lane.copy(role = role)
        }
    }
    return if (changed) copy(lanes = updated) else this
}

private fun inferredLaneRole(lane: KanbanLane): KanbanLaneRole? = when {
    lane.id.equals("todo", ignoreCase = true) -> KanbanLaneRole.Todo
    lane.id.equals("doing", ignoreCase = true) -> KanbanLaneRole.Doing
    isCompletedKanbanLaneHeuristic(lane.id, lane.name) -> KanbanLaneRole.Done
    else -> null
}

/** Mirrors the UI heuristic without depending on the feature module. */
fun isCompletedKanbanLaneHeuristic(laneId: String, laneName: String): Boolean {
    if (laneId.equals("done", ignoreCase = true)) return true
    val name = laneName.trim()
    if (name.isEmpty()) return false
    val lower = name.lowercase()
    if (Regex("""\b(incomplete|undone|not[\s-]+done|not[\s-]+complete[d]?)\b""").containsMatchIn(lower)) {
        return false
    }
    return Regex("""\b(done|complete|completed|finished)\b""").containsMatchIn(lower)
}

fun KanbanBoard.laneForRole(role: KanbanLaneRole): KanbanLane? =
    lanes.firstOrNull { it.role == role }

/** Resolves the mirror target lane; Blocked falls back to Doing when no Blocked-role lane exists. */
fun KanbanBoard.mirrorLaneFor(role: KanbanLaneRole): KanbanLane? = when (role) {
    KanbanLaneRole.Blocked -> laneForRole(KanbanLaneRole.Blocked) ?: laneForRole(KanbanLaneRole.Doing)
    else -> laneForRole(role)
}

fun KanbanBoard.findCard(cardId: String): KanbanCard? =
    lanes.asSequence().flatMap { it.cards.asSequence() }.firstOrNull { it.id == cardId }

fun KanbanBoard.childCards(parentCardId: String): List<KanbanCard> =
    lanes.flatMap { it.cards }.filter { it.parentCardId == parentCardId }
