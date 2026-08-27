package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.andy.model.defaultKanbanLanes
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
    private val _boards = MutableStateFlow(store.loadAllKanbanBoards())
    override val boards: StateFlow<Map<String, KanbanBoard>> = _boards.asStateFlow()
    private val saveMutex = Mutex()

    override fun addLane(projectId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        mutate(projectId) { current ->
            current.copy(lanes = current.lanes + KanbanLane(id = nextId("lane", current), name = trimmed))
        }
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

    override fun addCard(projectId: String, laneId: String, title: String, description: String, tags: List<String>) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        val now = currentTimeMillis()
        mutate(projectId) { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    if (lane.id != laneId) lane else {
                        lane.copy(
                            cards = lane.cards + KanbanCard(
                                id = nextId("card", current),
                                title = trimmedTitle,
                                description = description.trim(),
                                tags = normalizeTags(tags),
                                createdAtMillis = now,
                                updatedAtMillis = now,
                            ),
                        )
                    }
                },
            )
        }
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
            val card = current.lanes.flatMap { it.cards }.firstOrNull { it.id == cardId } ?: return@mutate current
            val lanesWithoutCard = current.lanes.map { lane ->
                lane.copy(cards = lane.cards.filterNot { it.id == cardId })
            }
            val targetLaneIndex = lanesWithoutCard.indexOfFirst { it.id == toLaneId }
            if (targetLaneIndex < 0) return@mutate current
            val targetLane = lanesWithoutCard[targetLaneIndex]
            val insertIndex = toIndex.coerceIn(0, targetLane.cards.size)
            val updatedCards = targetLane.cards.toMutableList().apply {
                add(insertIndex, card.copy(updatedAtMillis = currentTimeMillis()))
            }
            val updatedTarget = targetLane.copy(cards = updatedCards)
            lanesWithoutCard.toMutableList().apply {
                this[targetLaneIndex] = updatedTarget
            }.let { current.copy(lanes = it) }
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
}

internal fun emptyKanbanBoard(): KanbanBoard = KanbanBoard(lanes = defaultKanbanLanes())
