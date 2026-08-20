package app.andy.desktop.service

import app.andy.model.Automation
import app.andy.model.AutomationLaunchSnapshot
import app.andy.model.AutomationSchedule
import app.andy.model.AgentKind
import app.andy.model.AutomationIntervalUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpAutomationToolsTest {
    @Test
    fun parsesNamedSchedules() {
        assertEquals(
            AutomationSchedule.Manual,
            automationScheduleFromToolArgs(mapOf("schedule" to JsonPrimitive("manual"))),
        )
        assertEquals(
            AutomationSchedule.Hourly,
            automationScheduleFromToolArgs(mapOf("schedule" to JsonPrimitive("hourly"))),
        )
        assertEquals(
            AutomationSchedule.Daily,
            automationScheduleFromToolArgs(mapOf("schedule" to JsonPrimitive("daily"))),
        )
        assertEquals(
            AutomationSchedule.Weekdays,
            automationScheduleFromToolArgs(mapOf("schedule" to JsonPrimitive("weekdays"))),
        )
        assertEquals(
            AutomationSchedule.Weekly(5),
            automationScheduleFromToolArgs(
                mapOf(
                    "schedule" to JsonPrimitive("weekly"),
                    "weeklyDayOfWeek" to JsonPrimitive(5),
                ),
            ),
        )
        val interval = automationScheduleFromToolArgs(
            mapOf(
                "schedule" to JsonPrimitive("interval"),
                "intervalEvery" to JsonPrimitive(2),
                "intervalUnit" to JsonPrimitive("Hours"),
            ),
            nowMillis = 42L,
        )
        assertEquals(AutomationSchedule.Interval(2, AutomationIntervalUnit.Hours, 42L), interval)
        assertEquals(
            AutomationSchedule.Cron("*/30 * * * *"),
            automationScheduleFromToolArgs(mapOf("cron" to JsonPrimitive("*/30 * * * *"))),
        )
    }

    @Test
    fun onceUsesExplicitMillisOrNextWallClock() {
        val explicit = automationScheduleFromToolArgs(
            mapOf(
                "schedule" to JsonPrimitive("once"),
                "onceAtMillis" to JsonPrimitive(99L),
            ),
        )
        assertEquals(AutomationSchedule.Once(99L), explicit)
        val wall = automationScheduleFromToolArgs(
            mapOf("schedule" to JsonPrimitive("once")),
            timeZone = "UTC",
            runHour = 9,
            runMinute = 0,
            nowMillis = 1_775_000_000_000L,
        )
        assertTrue(wall is AutomationSchedule.Once)
        assertTrue(wall.atMillis > 1_775_000_000_000L)
    }

    @Test
    fun updateWithoutScheduleKeepsExisting() {
        val existing = Automation(
            id = "auto-1",
            projectId = "garden",
            title = "Triage",
            prompt = "look",
            schedule = AutomationSchedule.Weekdays,
            timeZone = "UTC",
            launch = AutomationLaunchSnapshot(agent = AgentKind.Codex.name),
            createdAtMillis = 1,
            updatedAtMillis = 1,
        )
        assertEquals(
            AutomationSchedule.Weekdays,
            automationScheduleFromToolArgs(emptyMap(), existing = existing),
        )
    }

    @Test
    fun toolNamesStayStable() {
        assertEquals(
            listOf(
                "automation.list",
                "automation.get",
                "automation.create",
                "automation.update",
                "automation.pause",
                "automation.resume",
                "automation.delete",
                "automation.run",
            ),
            automationToolNames(),
        )
    }
}
