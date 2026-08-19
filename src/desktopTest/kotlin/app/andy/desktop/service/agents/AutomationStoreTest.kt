package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.Automation
import app.andy.model.AutomationLaunchSnapshot
import app.andy.model.AutomationSchedule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutomationStoreTest {
    @Test
    fun upgradesPreAutomationDatabaseAndRoundTrips() {
        val dir = File.createTempFile("andy-auto-upgrade", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val dbFile = File(dir, "agents.db")
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
                "jdbc:sqlite:${dbFile.absolutePath}",
                java.util.Properties(),
            ).use { driver ->
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
            assertTrue(store.loadAllAutomations().isEmpty())
            val automation = Automation(
                id = "auto-1",
                projectId = "garden",
                title = "Triage",
                prompt = "look",
                schedule = AutomationSchedule.Daily,
                timeZone = "UTC",
                launch = AutomationLaunchSnapshot(agent = AgentKind.Codex.name),
                createdAtMillis = 1,
                updatedAtMillis = 2,
            )
            store.saveAutomation(automation)
            assertEquals(listOf(automation), store.loadAllAutomations())
            val withHistory = automation.copy(
                paused = true,
                pauseReason = "Stopped after 3 consecutive failures",
                nextRunAtMillis = 99L,
                fireCount = 3,
                runs = listOf(
                    app.andy.model.AutomationRunRecord(
                        id = "run-1",
                        taskId = "task-1",
                        startedAtMillis = 10,
                        finishedAtMillis = 11,
                        outcome = "error",
                        detail = "boom",
                    ),
                ),
            )
            store.saveAutomation(withHistory)
            assertEquals(listOf(withHistory), store.loadAllAutomations())
        } finally {
            dir.deleteRecursively()
        }
    }
}
