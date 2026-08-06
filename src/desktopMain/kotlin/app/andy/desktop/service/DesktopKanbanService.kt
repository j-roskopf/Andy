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

class DesktopKanbanService(
    private val store: DesktopAgentTaskStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : KanbanService {
    private val _board = MutableStateFlow(store.loadKanbanBoard() ?: KanbanBoard())
    override val board: StateFlow<KanbanBoard> = _board.asStateFlow()

    override fun addLane(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        mutate { current ->
            current.copy(lanes = current.lanes + KanbanLane(id = nextId("lane", current), name = trimmed))
        }
    }

    override fun renameLane(laneId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        mutate { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    if (lane.id == laneId) lane.copy(name = trimmed) else lane
                },
            )
        }
    }

    override fun deleteLane(laneId: String) {
        mutate { current ->
            if (current.lanes.size <= 1) return@mutate current
            current.copy(lanes = current.lanes.filterNot { it.id == laneId })
        }
    }

    override fun moveLane(laneId: String, direction: KanbanLaneDirection) {
        mutate { current ->
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

    override fun addCard(laneId: String, title: String, description: String, tags: List<String>) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        val now = currentTimeMillis()
        mutate { current ->
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

    override fun updateCard(cardId: String, title: String, description: String, tags: List<String>) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isBlank()) return
        val now = currentTimeMillis()
        mutate { current ->
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

    override fun deleteCard(cardId: String) {
        mutate { current ->
            current.copy(
                lanes = current.lanes.map { lane ->
                    lane.copy(cards = lane.cards.filterNot { it.id == cardId })
                },
            )
        }
    }

    override fun moveCard(cardId: String, toLaneId: String, toIndex: Int) {
        mutate { current ->
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

    private fun mutate(transform: (KanbanBoard) -> KanbanBoard) {
        val updated = transform(_board.value)
        if (updated == _board.value) return
        _board.value = updated
        scope.launch { store.saveKanbanBoard(updated) }
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
