package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.andy.service.KanbanLaneDirection
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopKanbanServiceTest {
    private val projectId = "project-1"

    private fun withService(block: (DesktopKanbanService) -> Unit) {
        val dir = File.createTempFile("andy-kanban-service", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            block(DesktopKanbanService(store))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun addRenameAndDeleteLanes() = withService { service ->
        service.addLane(projectId, "Backlog")
        assertEquals(listOf("To-Do", "Doing", "Done", "Backlog"), laneNames(service.board()))

        service.renameLane(projectId, "todo", "Inbox")
        assertEquals("Inbox", service.board().lanes.first().name)

        service.deleteLane(projectId, "done")
        assertEquals(3, service.board().lanes.size)
    }

    @Test
    fun moveLaneRespectsBoundaries() = withService { service ->
        val initial = laneNames(service.board())
        service.moveLane(projectId, "todo", KanbanLaneDirection.Left)
        assertEquals(initial, laneNames(service.board()))

        service.moveLane(projectId, "done", KanbanLaneDirection.Right)
        assertEquals(initial, laneNames(service.board()))

        service.moveLane(projectId, "done", KanbanLaneDirection.Left)
        assertEquals(listOf("To-Do", "Done", "Doing"), laneNames(service.board()))
    }

    @Test
    fun cardCrud() = withService { service ->
        service.addCard(projectId, "todo", "First", "Details", listOf("alpha", "beta"))
        val card = service.board().lanes.first().cards.single()
        assertEquals("First", card.title)
        assertEquals(listOf("alpha", "beta"), card.tags)

        service.updateCard(projectId, card.id, "Updated", "New details", listOf("gamma"))
        val updated = service.board().lanes.first().cards.single()
        assertEquals("Updated", updated.title)
        assertEquals(listOf("gamma"), updated.tags)

        service.deleteCard(projectId, card.id)
        assertTrue(service.board().lanes.first().cards.isEmpty())
    }

    @Test
    fun moveCardWithinAndAcrossLanes() = withService { service ->
        seedBoard(service)
        val todoLane = service.board().lanes.first { it.id == "todo" }
        val first = todoLane.cards.first()
        val second = todoLane.cards[1]
        val third = todoLane.cards[2]

        service.moveCard(projectId, third.id, "todo", 0)
        assertEquals(listOf(third.id, first.id, second.id), cardIds(service.board(), "todo"))

        service.moveCard(projectId, first.id, "todo", 2)
        assertEquals(listOf(third.id, second.id, first.id), cardIds(service.board(), "todo"))

        service.moveCard(projectId, second.id, "doing", 0)
        assertEquals(second.id, cardIds(service.board(), "doing").first())
        assertEquals(listOf(third.id, first.id), cardIds(service.board(), "todo"))
    }

    @Test
    fun deleteLaneRemovesItsCards() = withService { service ->
        seedBoard(service)
        val backlogId = service.board().lanes.first { it.name == "Backlog" }.id
        service.deleteLane(projectId, "todo")
        assertEquals(3, service.board().lanes.size)
        assertTrue(service.board().lanes.none { it.id == "todo" })
        service.deleteLane(projectId, backlogId)
        service.deleteLane(projectId, "doing")
        assertEquals(1, service.board().lanes.size)
        service.deleteLane(projectId, service.board().lanes.single().id)
        assertEquals(1, service.board().lanes.size, "last lane delete is a no-op")
    }

    @Test
    fun persistsAcrossReload() {
        val dir = File.createTempFile("andy-kanban-persist", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val db = File(dir, "agents.db")
            val store = DesktopAgentTaskStore(db)
            val service = DesktopKanbanService(store)
            service.addLane(projectId, "QA")
            service.addCard(projectId, "todo", "Persist me", "desc", listOf("save"))
            runBlocking { service.flushPersist(projectId) }
            val reloaded = DesktopKanbanService(DesktopAgentTaskStore(db))
            assertTrue(reloaded.board().lanes.any { it.name == "QA" })
            val todoCards = reloaded.board().lanes.first { it.id == "todo" }.cards
            assertEquals(1, todoCards.size)
            assertEquals("Persist me", todoCards.single().title)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun projectsHaveIndependentBoards() = withService { service ->
        service.addLane(projectId, "One only")
        service.addCard("project-2", "todo", "Two only", "", emptyList())

        assertTrue(service.boards.value.getValue(projectId).lanes.any { it.name == "One only" })
        assertTrue(service.boards.value.getValue("project-2").lanes.none { it.name == "One only" })
    }

    @Test
    fun linkChatKeepsHistoryAndUpdatesActiveChat() = withService { service ->
        service.addCard(projectId, "todo", "Assigned", "", emptyList())
        val cardId = service.board().lanes.first().cards.single().id
        service.linkChat(projectId, cardId, "chat-1")
        service.linkChat(projectId, cardId, "chat-2")

        val card = service.board().lanes.first().cards.single()
        assertEquals(listOf("chat-1", "chat-2"), card.linkedChatTaskIds)
        assertEquals("chat-2", card.activeChatTaskId)
    }

    @Test
    fun deleteBoardRemovesMemoryAndPersistedRow() {
        val dir = File.createTempFile("andy-kanban-delete", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val db = File(dir, "agents.db")
            val service = DesktopKanbanService(DesktopAgentTaskStore(db))
            service.addCard(projectId, "todo", "Delete me", "", emptyList())
            runBlocking { service.flushPersist(projectId) }
            service.deleteBoard(projectId)
            runBlocking { service.flushPersist(projectId) }

            assertTrue(projectId !in service.boards.value)
            assertTrue(projectId !in DesktopAgentTaskStore(db).loadAllKanbanBoards())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun seedBoard(service: DesktopKanbanService) {
        service.addLane(projectId, "Backlog")
        service.addCard(projectId, "todo", "One", "", emptyList())
        service.addCard(projectId, "todo", "Two", "", emptyList())
        service.addCard(projectId, "todo", "Three", "", emptyList())
        service.addCard(projectId, "doing", "Doing item", "", emptyList())
    }

    private fun DesktopKanbanService.board(): KanbanBoard = boards.value[projectId] ?: KanbanBoard()

    private fun laneNames(board: KanbanBoard) = board.lanes.map { it.name }

    private fun cardIds(board: KanbanBoard, laneId: String) =
        board.lanes.first { it.id == laneId }.cards.map { it.id }
}
