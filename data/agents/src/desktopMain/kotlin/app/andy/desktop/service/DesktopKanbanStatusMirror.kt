package app.andy.desktop.service

import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLaneRole
import app.andy.model.findCard
import app.andy.model.mirrorLaneFor
import app.andy.service.AgentRunService
import app.andy.service.KanbanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Projects linked-chat [AgentStatus] onto kanban card lanes for the human watching the board.
 * Not the swarm control path — leads learn about workers via ChatCompletionNotifier / chat.await.
 */
@OptIn(FlowPreview::class)
class DesktopKanbanStatusMirror(
    private val agentRuns: AgentRunService,
    private val kanban: KanbanService,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 250L,
) {
    fun start() {
        scope.launch {
            combine(agentRuns.tasks, kanban.boards) { tasks, boards ->
                MirrorSnapshot(tasks = tasks.associateBy { it.id }, boards = boards)
            }
                .debounce(debounceMs)
                .distinctUntilChanged()
                .collect { snapshot -> applyMirror(snapshot) }
        }
    }

    internal fun applyMirror(snapshot: MirrorSnapshot) {
        snapshot.boards.forEach { (projectId, board) ->
            val allCards = board.lanes.flatMap { it.cards }
            val childrenByParent = allCards.filter { it.parentCardId != null }
                .groupBy { it.parentCardId!! }

            allCards.forEach { card ->
                if (card.lanePinned) return@forEach
                if (childrenByParent.containsKey(card.id)) return@forEach
                val chatId = card.activeChatTaskId ?: return@forEach
                val status = snapshot.tasks[chatId]?.status ?: return@forEach
                kanban.mirrorCardStatus(projectId, card.id, status)
            }

            childrenByParent.forEach { (parentId, children) ->
                val parent = board.findCard(parentId) ?: return@forEach
                if (parent.lanePinned) return@forEach
                val childStatuses = children.mapNotNull { child ->
                    child.activeChatTaskId?.let { snapshot.tasks[it]?.status }
                        ?: laneRoleAsStatus(board, child)
                }
                if (childStatuses.isEmpty()) return@forEach
                val rollup = rollupStatus(childStatuses) ?: return@forEach
                when (kanban) {
                    is DesktopKanbanService -> kanban.mirrorParentRollup(projectId, parent.id, rollup)
                    else -> Unit
                }
                if (rollup == AgentStatus.Done && kanban is DesktopKanbanService) {
                    val swarmId = parent.swarmRunId ?: children.firstNotNullOfOrNull { it.swarmRunId }
                    if (swarmId != null) kanban.clearSwarmPins(projectId, swarmId)
                }
            }
        }
    }

    data class MirrorSnapshot(
        val tasks: Map<String, AgentTask>,
        val boards: Map<String, KanbanBoard>,
    )
}

internal fun rollupStatus(statuses: List<AgentStatus>): AgentStatus? {
    if (statuses.isEmpty()) return null
    if (statuses.any { it == AgentStatus.Blocked || it == AgentStatus.Error }) return AgentStatus.Blocked
    if (statuses.any { it == AgentStatus.Working }) return AgentStatus.Working
    if (statuses.all { it == AgentStatus.Done }) return AgentStatus.Done
    return null
}

private fun laneRoleAsStatus(board: KanbanBoard, card: KanbanCard): AgentStatus? {
    val lane = board.lanes.firstOrNull { it.cards.any { c -> c.id == card.id } } ?: return null
    return when (lane.role) {
        KanbanLaneRole.Doing -> AgentStatus.Working
        KanbanLaneRole.Blocked -> AgentStatus.Blocked
        KanbanLaneRole.Done -> AgentStatus.Done
        KanbanLaneRole.Todo, null -> null
    }
}
