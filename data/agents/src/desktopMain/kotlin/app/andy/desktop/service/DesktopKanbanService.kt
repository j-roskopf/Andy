package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.AgentStatus
import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.andy.model.KanbanLaneRole
import app.andy.model.childCards
import app.andy.model.defaultKanbanLanes
import app.andy.model.findCard
import app.andy.model.mirrorLaneFor
import app.andy.model.withBackfilledLaneRoles
import app.andy.service.KanbanLaneDirection
import app.andy.service.KanbanService
import app.andy.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DesktopKanbanService(
    private val store: DesktopAgentTaskStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : KanbanService {
    private val saveMutex = Mutex()
    private val _boards = MutableStateFlow(loadAndBackfill())
    override val boards: StateFlow<Map<String, KanbanBoard>> = _boards.asStateFlow()

    private fun loadAndBackfill(): Map<String, KanbanBoard> {
        val loaded = store.loadAllKanbanBoards()
        val backfilled = loaded.mapValues { (_, board) -> board.withBackfilledLaneRoles() }
        // Persist one-time role backfill so subsequent loads skip the heuristic.
        backfilled.forEach { (projectId, board) ->
            if (loaded[projectId] != board) {
                scope.launch {
                    saveMutex.withLock { store.saveKanbanBoard(projectId, board) }
                }
            }
        }
        return backfilled
    }

    override fun addLane(projectId: String, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        var createdId: String? = null
        mutate(projectId) { current ->
            val id = nextId("lane", current)
            createdId = id
            current.copy(lanes = current.lanes + KanbanLane(id = id, name = trimmed))
        }
        return createdId
    }

    override fun renameLane(projectId: String, laneId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    if (lane.id == laneId) lane.copy(name = trimmed) else lane
                },
            )
        }
    }

    override fun deleteLane(projectId: String, laneId: String) {
        mutate(projectId) { current ->
            if (current.lanes.size <= 1) return@mutate current
            current.copy(lanes = current.lanes.filterNot { it.id == laneId })
        }
    }

    override fun moveLane(projectId: String, laneId: String, direction: KanbanLaneDirection) {
        mutate(projectId) { current ->
            val index = current.lanes.indexOfFirst { it.id == laneId }
            if (index < 0) return@mutate current
            val target = when (direction) {
                KanbanLaneDirection.Left -> index - 1
                KanbanLaneDirection.Right -> index + 1
            }
            if (target !in current.lanes.indices) return@mutate current
            val lanes = current.lanes.toMutableList()
            val lane = lanes.removeAt(index)
            lanes.add(target, lane)
            current.copy(lanes = lanes)
        }
    }

    override fun setLaneRole(projectId: String, laneId: String, role: KanbanLaneRole?) {
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    if (lane.id == laneId) lane.copy(role = role) else lane
                },
            )
        }
    }

    override fun addCard(
        projectId: String,
        laneId: String,
        title: String,
        description: String,
        tags: List<String>,
        parentCardId: String?,
        swarmRunId: String?,
    ): String? {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return null
        val now = currentTimeMillis()
        var createdId: String? = null
        mutate(projectId) { current ->
            if (current.lanes.none { it.id == laneId }) return@mutate current
            val id = nextId("card", current)
            createdId = id
            current.copy(
                lanes = current.lanes.map { lane ->
                    if (lane.id != laneId) lane else {
                        lane.copy(
                            cards = lane.cards + KanbanCard(
                                id = id,
                                title = trimmedTitle,
                                description = description.trim(),
                                tags = normalizeTags(tags),
                                createdAtMillis = now,
                                updatedAtMillis = now,
                                parentCardId = parentCardId,
                                swarmRunId = swarmRunId,
                            ),
                        )
                    }
                },
            )
        }
        return createdId
    }

    override fun updateCard(projectId: String, cardId: String, title: String, description: String, tags: List<String>) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        val now = currentTimeMillis()
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    lane.copy(
                        cards = lane.cards.map { card ->
                            if (card.id != cardId) card else {
                                card.copy(
                                    title = trimmedTitle,
                                    description = description.trim(),
                                    tags = normalizeTags(tags),
                                    updatedAtMillis = now,
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    override fun deleteCard(projectId: String, cardId: String) {
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    lane.copy(cards = lane.cards.filterNot { it.id == cardId })
                },
            )
        }
    }

    override fun moveCard(projectId: String, cardId: String, toLaneId: String, toIndex: Int) {
        mutate(projectId) { current ->
            relocateCard(current, cardId, toLaneId, toIndex, pin = true) ?: current
        }
    }

    override fun setCardPinned(projectId: String, cardId: String, pinned: Boolean) {
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    lane.copy(
                        cards = lane.cards.map { card ->
                            if (card.id != cardId) card else {
                                card.copy(
                                    lanePinned = pinned,
                                    updatedAtMillis = currentTimeMillis(),
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    override fun mirrorCardStatus(projectId: String, cardId: String, status: AgentStatus) {
        mirrorCardStatus(projectId, cardId, status, allowParent = false)
    }

    /**
     * Parent rollup moves a card that has children. Same pin respect as [mirrorCardStatus],
     * but skips the "has children → don't mirror" guard.
     */
    fun mirrorParentRollup(projectId: String, cardId: String, status: AgentStatus) {
        mirrorCardStatus(projectId, cardId, status, allowParent = true)
    }

    private fun mirrorCardStatus(
        projectId: String,
        cardId: String,
        status: AgentStatus,
        allowParent: Boolean,
    ) {
        val role = status.toKanbanLaneRole() ?: return
        mutate(projectId) { current ->
            val card = current.findCard(cardId) ?: return@mutate current
            // Pinned cards are never mirror targets (decision 15).
            if (card.lanePinned) return@mutate current
            // Leaf-only by default; parents are rolled up via [mirrorParentRollup].
            if (!allowParent && current.childCards(cardId).isNotEmpty()) return@mutate current
            val targetLane = current.mirrorLaneFor(role) ?: return@mutate current
            val alreadyThere = current.lanes.any { it.id == targetLane.id && it.cards.any { c -> c.id == cardId } }
            if (alreadyThere) return@mutate current
            relocateCard(current, cardId, targetLane.id, toIndex = Int.MAX_VALUE, pin = false) ?: current
        }
    }

    /**
     * Clears [KanbanCard.lanePinned] for every card in [swarmRunId] once the parent is Done.
     * Called by the status mirror after parent rollup.
     */
    fun clearSwarmPins(projectId: String, swarmRunId: String) {
        if (swarmRunId.isBlank()) return
        mutate(projectId) { current ->
            var changed = false
            val lanes = current.lanes.map { lane ->
                lane.copy(
                    cards = lane.cards.map { card ->
                        if (card.swarmRunId == swarmRunId && card.lanePinned) {
                            changed = true
                            card.copy(lanePinned = false, updatedAtMillis = currentTimeMillis())
                        } else {
                            card
                        }
                    },
                )
            }
            if (changed) current.copy(lanes = lanes) else current
        }
    }

    override fun linkChat(projectId: String, cardId: String, chatTaskId: String) {
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    lane.copy(
                        cards = lane.cards.map { card ->
                            if (card.id != cardId) {
                                card
                            } else {
                                card.copy(
                                    linkedChatTaskIds = card.linkedChatTaskIds + chatTaskId,
                                    activeChatTaskId = chatTaskId,
                                    updatedAtMillis = currentTimeMillis(),
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    override fun deleteBoard(projectId: String) {
        _boards.value = _boards.value - projectId
        scope.launch {
            saveMutex.withLock {
                store.deleteKanbanBoard(projectId)
            }
        }
    }

    private fun mutate(projectId: String, transform: (KanbanBoard) -> KanbanBoard) {
        val current = _boards.value[projectId] ?: KanbanBoard()
        val updated = transform(current)
        if (updated == current) return
        _boards.value = _boards.value + (projectId to updated)
        // Persist the latest board under a mutex. Saving the mutate-time snapshot can
        // reorder and let an older write clobber a newer one under test/CI load.
        scope.launch {
            saveMutex.withLock {
                store.saveKanbanBoard(projectId, _boards.value[projectId] ?: return@withLock)
            }
        }
    }

    /** Wait for queued persists and write the latest in-memory board (tests / harness). */
    suspend fun flushPersist(projectId: String) {
        saveMutex.withLock {
            val board = _boards.value[projectId]
            if (board == null) {
                store.deleteKanbanBoard(projectId)
            } else {
                store.saveKanbanBoard(projectId, board)
            }
        }
    }

    private fun nextId(prefix: String, board: KanbanBoard): String {
        val existing = buildSet {
            board.lanes.forEach { lane ->
                add(lane.id)
                lane.cards.forEach { add(it.id) }
            }
        }
        var id = "$prefix-${currentTimeMillis()}"
        var index = 2
        while (id in existing) {
            id = "$prefix-${currentTimeMillis()}-$index"
            index++
        }
        return id
    }

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        internal fun relocateCard(
            board: KanbanBoard,
            cardId: String,
            toLaneId: String,
            toIndex: Int,
            pin: Boolean,
        ): KanbanBoard? {
            val card = board.lanes.flatMap { it.cards }.firstOrNull { it.id == cardId } ?: return null
            val lanesWithoutCard = board.lanes.map { lane ->
                lane.copy(cards = lane.cards.filterNot { it.id == cardId })
            }
            val targetLaneIndex = lanesWithoutCard.indexOfFirst { it.id == toLaneId }
            if (targetLaneIndex < 0) return null
            val targetLane = lanesWithoutCard[targetLaneIndex]
            val insertIndex = toIndex.coerceIn(0, targetLane.cards.size)
            val moved = card.copy(
                updatedAtMillis = currentTimeMillis(),
                lanePinned = if (pin) true else card.lanePinned,
            )
            val updatedCards = targetLane.cards.toMutableList().apply {
                add(insertIndex, moved)
            }
            val updatedTarget = targetLane.copy(cards = updatedCards)
            return lanesWithoutCard.toMutableList().apply {
                this[targetLaneIndex] = updatedTarget
            }.let { board.copy(lanes = it) }
        }
    }
}

internal fun AgentStatus.toKanbanLaneRole(): KanbanLaneRole? = when (this) {
    AgentStatus.Working -> KanbanLaneRole.Doing
    AgentStatus.Blocked, AgentStatus.Error -> KanbanLaneRole.Blocked
    AgentStatus.Done -> KanbanLaneRole.Done
}

internal fun emptyKanbanBoard(): KanbanBoard = KanbanBoard(lanes = defaultKanbanLanes())
