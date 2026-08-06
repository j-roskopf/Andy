package app.andy.desktop.service

import app.andy.desktop.service.agents.DesktopAgentTaskStore
import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.andy.service.KanbanLaneDirection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopKanbanServiceTest {
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
        service.addLane("Backlog")
        assertEquals(listOf("To-Do", "Doing", "Done", "Backlog"), laneNames(service.board.value))

        service.renameLane("todo", "Inbox")
        assertEquals("Inbox", service.board.value.lanes.first().name)

        service.deleteLane("done")
        assertEquals(3, service.board.value.lanes.size)
    }

    @Test
    fun moveLaneRespectsBoundaries() = withService { service ->
        val initial = laneNames(service.board.value)
        service.moveLane("todo", KanbanLaneDirection.Left)
        assertEquals(initial, laneNames(service.board.value))

        service.moveLane("done", KanbanLaneDirection.Right)
        assertEquals(initial, laneNames(service.board.value))

        service.moveLane("done", KanbanLaneDirection.Left)
        assertEquals(listOf("To-Do", "Done", "Doing"), laneNames(service.board.value))
    }

    @Test
    fun cardCrud() = withService { service ->
        service.addCard("todo", "First", "Details", listOf("alpha", "beta"))
        val card = service.board.value.lanes.first().cards.single()
        assertEquals("First", card.title)
        assertEquals(listOf("alpha", "beta"), card.tags)

        service.updateCard(card.id, "Updated", "New details", listOf("gamma"))
        val updated = service.board.value.lanes.first().cards.single()
        assertEquals("Updated", updated.title)
        assertEquals(listOf("gamma"), updated.tags)

        service.deleteCard(card.id)
        assertTrue(service.board.value.lanes.first().cards.isEmpty())
    }

    @Test
    fun moveCardWithinAndAcrossLanes() = withService { service ->
        seedBoard(service)
        val todoLane = service.board.value.lanes.first { it.id == "todo" }
        val doingLane = service.board.value.lanes.first { it.id == "doing" }
        val first = todoLane.cards.first()
        val second = todoLane.cards[1]
        val third = todoLane.cards[2]

        service.moveCard(third.id, "todo", 0)
        assertEquals(listOf(third.id, first.id, second.id), cardIds(service.board.value, "todo"))

        service.moveCard(first.id, "todo", 2)
        assertEquals(listOf(third.id, second.id, first.id), cardIds(service.board.value, "todo"))

        service.moveCard(second.id, "doing", 0)
        assertEquals(second.id, cardIds(service.board.value, "doing").first())
        assertEquals(listOf(third.id, first.id), cardIds(service.board.value, "todo"))
    }

    @Test
    fun deleteLaneRemovesItsCards() = withService { service ->
        seedBoard(service)
        val backlogId = service.board.value.lanes.first { it.name == "Backlog" }.id
        service.deleteLane("todo")
        assertEquals(3, service.board.value.lanes.size)
        assertTrue(service.board.value.lanes.none { it.id == "todo" })
        service.deleteLane(backlogId)
        service.deleteLane("doing")
        assertEquals(1, service.board.value.lanes.size)
        service.deleteLane(service.board.value.lanes.single().id)
        assertEquals(1, service.board.value.lanes.size, "last lane delete is a no-op")
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
            service.addLane("QA")
            service.addCard("todo", "Persist me", "desc", listOf("save"))
            store.saveKanbanBoard(service.board.value)
            val reloaded = DesktopKanbanService(DesktopAgentTaskStore(db))
            assertTrue(reloaded.board.value.lanes.any { it.name == "QA" })
            assertEquals("Persist me", reloaded.board.value.lanes.first { it.id == "todo" }.cards.single().title)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun seedBoard(service: DesktopKanbanService) {
        service.addLane("Backlog")
        service.addCard("todo", "One", "", emptyList())
        service.addCard("todo", "Two", "", emptyList())
        service.addCard("todo", "Three", "", emptyList())
        service.addCard("doing", "Doing item", "", emptyList())
    }

    private fun laneNames(board: KanbanBoard) = board.lanes.map { it.name }

    private fun cardIds(board: KanbanBoard, laneId: String) =
        board.lanes.first { it.id == laneId }.cards.map { it.id }
}
