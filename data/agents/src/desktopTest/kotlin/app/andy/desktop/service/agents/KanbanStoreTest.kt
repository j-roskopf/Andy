package app.andy.desktop.service.agents

import app.andy.model.KanbanBoard
import app.andy.model.KanbanCard
import app.andy.model.KanbanLane
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KanbanStoreTest {
    @Test
    fun upgradesPreKanbanDatabaseWithoutCrashing() {
        val dir = File.createTempFile("andy-kanban-upgrade", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val dbFile = File(dir, "agents.db")
            // Simulate an agents.db created before kanban_board existed.
            JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { driver ->
                driver.execute(
                    null,
                    """
                    CREATE TABLE store_meta (
                      key TEXT NOT NULL PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """.trimIndent(),
                    0,
                )
            }
            val store = DesktopAgentTaskStore(dbFile)
            assertTrue(store.loadAllKanbanBoards().isEmpty())
            val board = KanbanBoard(
                lanes = listOf(KanbanLane(id = "todo", name = "To-Do")),
            )
            store.saveKanbanBoard("project-1", board)
            assertEquals(board, store.loadAllKanbanBoards()["project-1"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripsBoardWithLanesCardsAndTags() {
        val dir = File.createTempFile("andy-kanban-store", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            val board = KanbanBoard(
                lanes = listOf(
                    KanbanLane(
                        id = "todo",
                        name = "To-Do",
                        cards = listOf(
                            KanbanCard(
                                id = "card-1",
                                title = "Ship kanban",
                                description = "Desktop-only v1",
                                tags = listOf("ui", "desktop"),
                                createdAtMillis = 1,
                                updatedAtMillis = 2,
                            ),
                        ),
                    ),
                    KanbanLane(id = "doing", name = "Doing"),
                    KanbanLane(
                        id = "done",
                        name = "Done",
                        cards = listOf(
                            KanbanCard(
                                id = "card-2",
                                title = "Done item",
                                description = "",
                                tags = emptyList(),
                                createdAtMillis = 3,
                                updatedAtMillis = 4,
                            ),
                        ),
                    ),
                ),
            )
            store.saveKanbanBoard("project-1", board)
            assertEquals(board, store.loadAllKanbanBoards()["project-1"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun returnsEmptyMapWhenNoBoardSaved() {
        val dir = File.createTempFile("andy-kanban-empty", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val store = DesktopAgentTaskStore(File(dir, "agents.db"))
            assertTrue(store.loadAllKanbanBoards().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dropsLegacySingletonBoardAndCreatesProjectKeyedTable() {
        val dir = File.createTempFile("andy-kanban-legacy", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val dbFile = File(dir, "agents.db")
            JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties()).use { driver ->
                driver.execute(
                    null,
                    """
                    CREATE TABLE kanban_board (
                      id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                      updated_at_millis INTEGER NOT NULL,
                      payload TEXT NOT NULL
                    )
                    """.trimIndent(),
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO kanban_board(id, updated_at_millis, payload) VALUES (1, 1, '{}')",
                    0,
                )
            }

            val store = DesktopAgentTaskStore(dbFile)
            assertTrue(store.loadAllKanbanBoards().isEmpty())
            store.saveKanbanBoard("project-1", KanbanBoard())
            assertTrue("project-1" in store.loadAllKanbanBoards())
            assertTrue(
                "project-1" in DesktopAgentTaskStore(dbFile).loadAllKanbanBoards(),
                "reopening an already-migrated database must not drop project boards",
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
