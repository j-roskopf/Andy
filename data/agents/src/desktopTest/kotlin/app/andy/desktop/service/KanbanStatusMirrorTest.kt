package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.AgentKind
import app.andy.model.AgentStatus
import app.andy.model.AgentTask
import app.andy.service.AgentRunService
import app.andy.service.UnavailableAgentRunService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KanbanStatusMirrorTest {
    private fun withMirror(
        block: (DesktopKanbanService, DesktopKanbanStatusMirror, MutableStateFlow<List<AgentTask>>) -> Unit,
    ) {
        val dir = File.createTempFile("andy-kanban-mirror", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            val kanban = DesktopKanbanService(store)
            val tasksFlow = MutableStateFlow<List<AgentTask>>(emptyList())
            val agents = object : AgentRunService by UnavailableAgentRunService {
                override val tasks: StateFlow<List<AgentTask>> = tasksFlow
            }
            val mirror = DesktopKanbanStatusMirror(
                agentRuns = agents,
                kanban = kanban,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                debounceMs = 0,
            )
            block(kanban, mirror, tasksFlow)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mapsWorkingToDoingLane() = withMirror { kanban, mirror, tasks ->
        val projectId = "p1"
        val cardId = kanban.addCard(projectId, "todo", "Work", "", emptyList())!!
        kanban.linkChat(projectId, cardId, "chat-1")
        tasks.value = listOf(task("chat-1", AgentStatus.Working))
        mirror.applyMirror(
            DesktopKanbanStatusMirror.MirrorSnapshot(
                tasks = tasks.value.associateBy { it.id },
                boards = kanban.boards.value,
            ),
        )
        val doing = kanban.boards.value.getValue(projectId).lanes.first { it.id == "doing" }
        assertTrue(doing.cards.any { it.id == cardId })
    }

    @Test
    fun blockedFallsBackToDoingWhenNoBlockedLane() = withMirror { kanban, mirror, tasks ->
        val projectId = "p1"
        val cardId = kanban.addCard(projectId, "todo", "Stuck", "", emptyList())!!
        kanban.linkChat(projectId, cardId, "chat-1")
        tasks.value = listOf(task("chat-1", AgentStatus.Blocked))
        mirror.applyMirror(
            DesktopKanbanStatusMirror.MirrorSnapshot(
                tasks = tasks.value.associateBy { it.id },
                boards = kanban.boards.value,
            ),
        )
        val doing = kanban.boards.value.getValue(projectId).lanes.first { it.id == "doing" }
        assertTrue(doing.cards.any { it.id == cardId })
    }

    @Test
    fun pinSuppressesMirror() = withMirror { kanban, mirror, tasks ->
        val projectId = "p1"
        val cardId = kanban.addCard(projectId, "todo", "Pinned", "", emptyList())!!
        kanban.linkChat(projectId, cardId, "chat-1")
        kanban.moveCard(projectId, cardId, "todo", 0) // pins
        assertTrue(
            kanban.boards.value.getValue(projectId).lanes.flatMap { it.cards }
                .single { it.id == cardId }.lanePinned,
        )
        tasks.value = listOf(task("chat-1", AgentStatus.Done))
        mirror.applyMirror(
            DesktopKanbanStatusMirror.MirrorSnapshot(
                tasks = tasks.value.associateBy { it.id },
                boards = kanban.boards.value,
            ),
        )
        val todo = kanban.boards.value.getValue(projectId).lanes.first { it.id == "todo" }
        assertTrue(todo.cards.any { it.id == cardId }, "pinned card must stay in todo")
    }

    @Test
    fun parentRollupAnyWorkingToDoing() = withMirror { kanban, mirror, tasks ->
        val projectId = "p1"
        val parentId = kanban.addCard(projectId, "todo", "Parent", "", emptyList(), swarmRunId = "s1")!!
        val childA = kanban.addCard(projectId, "todo", "A", "", emptyList(), parentCardId = parentId, swarmRunId = "s1")!!
        val childB = kanban.addCard(projectId, "todo", "B", "", emptyList(), parentCardId = parentId, swarmRunId = "s1")!!
        kanban.linkChat(projectId, childA, "chat-a")
        kanban.linkChat(projectId, childB, "chat-b")
        tasks.value = listOf(
            task("chat-a", AgentStatus.Working),
            task("chat-b", AgentStatus.Done),
        )
        mirror.applyMirror(
            DesktopKanbanStatusMirror.MirrorSnapshot(
                tasks = tasks.value.associateBy { it.id },
                boards = kanban.boards.value,
            ),
        )
        val board = kanban.boards.value.getValue(projectId)
        assertTrue(board.lanes.first { it.id == "doing" }.cards.any { it.id == parentId })
        assertTrue(board.lanes.first { it.id == "doing" }.cards.any { it.id == childA })
        assertTrue(board.lanes.first { it.id == "done" }.cards.any { it.id == childB })
    }

    @Test
    fun parentNotDoneWhileAnyChildUnresolved() = withMirror { kanban, mirror, tasks ->
        val projectId = "p1"
        val parentId = kanban.addCard(projectId, "todo", "Parent", "", emptyList(), swarmRunId = "s1")!!
        val childDone = kanban.addCard(
            projectId, "todo", "A", "", emptyList(), parentCardId = parentId, swarmRunId = "s1",
        )!!
        kanban.addCard(projectId, "todo", "B", "", emptyList(), parentCardId = parentId, swarmRunId = "s1")
        kanban.linkChat(projectId, childDone, "chat-a")
        tasks.value = listOf(task("chat-a", AgentStatus.Done))
        mirror.applyMirror(
            DesktopKanbanStatusMirror.MirrorSnapshot(
                tasks = tasks.value.associateBy { it.id },
                boards = kanban.boards.value,
            ),
        )
        val board = kanban.boards.value.getValue(projectId)
        assertTrue(
            board.lanes.first { it.id == "todo" }.cards.any { it.id == parentId },
            "parent must not close while an unspawned child remains",
        )
    }

    @Test
    fun parentDoneWhenAllChildrenResolved() = withMirror { kanban, mirror, tasks ->
        val projectId = "p1"
        val parentId = kanban.addCard(projectId, "todo", "Parent", "", emptyList(), swarmRunId = "s1")!!
        val childA = kanban.addCard(
            projectId, "todo", "A", "", emptyList(), parentCardId = parentId, swarmRunId = "s1",
        )!!
        val childB = kanban.addCard(
            projectId, "todo", "B", "", emptyList(), parentCardId = parentId, swarmRunId = "s1",
        )!!
        kanban.linkChat(projectId, childA, "chat-a")
        kanban.linkChat(projectId, childB, "chat-b")
        tasks.value = listOf(
            task("chat-a", AgentStatus.Done),
            task("chat-b", AgentStatus.Done),
        )
        mirror.applyMirror(
            DesktopKanbanStatusMirror.MirrorSnapshot(
                tasks = tasks.value.associateBy { it.id },
                boards = kanban.boards.value,
            ),
        )
        val board = kanban.boards.value.getValue(projectId)
        assertTrue(board.lanes.first { it.id == "done" }.cards.any { it.id == parentId })
    }

    @Test
    fun rollupStatusMapping() {
        assertEquals(AgentStatus.Blocked, rollupStatus(listOf(AgentStatus.Working, AgentStatus.Error)))
        assertEquals(AgentStatus.Working, rollupStatus(listOf(AgentStatus.Working, AgentStatus.Done)))
        assertEquals(AgentStatus.Done, rollupStatus(listOf(AgentStatus.Done, AgentStatus.Done)))
    }

    private fun task(id: String, status: AgentStatus) = AgentTask(
        id = id,
        title = id,
        prompt = "",
        agent = AgentKind.Codex,
        status = status,
        createdAtMillis = 1L,
    )
}
