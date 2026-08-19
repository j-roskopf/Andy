package app.andy.desktop.service.automations

import app.andy.model.AutomationIntervalUnit
import app.andy.model.AutomationSchedule
import app.andy.model.MinAutomationIntervalMillis
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationScheduleMathTest {
    @Test
    fun dailySkipsToTomorrowWhenTodaysSlotPassed() {
        val from = ZonedDateTime.of(2026, 8, 19, 10, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Daily,
            timeZone = "UTC",
            runHour = 9,
            runMinute = 0,
            fromExclusiveMillis = from,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC)
        assertEquals(20, nextTime.dayOfMonth)
        assertEquals(9, nextTime.hour)
    }

    @Test
    fun intervalRespectsFifteenMinuteFloor() {
        val start = 1_000_000L
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Interval(1, AutomationIntervalUnit.Minutes, start),
            timeZone = "UTC",
            runHour = 9,
            runMinute = 0,
            fromExclusiveMillis = start,
            lastFiredAtMillis = start,
        )!!
        assertEquals(start + MinAutomationIntervalMillis, next)
    }

    @Test
    fun cronHourlyMatches() {
        val from = ZonedDateTime.of(2026, 8, 19, 9, 0, 0, 0, ZoneOffset.UTC)
        val next = nextCron("0 * * * *", from)!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC)
        assertEquals(10, nextTime.hour)
        assertEquals(0, nextTime.minute)
    }

    @Test
    fun catchUpUsesNowWhenMissed() {
        assertEquals(50L, catchUpOccurrence(10L, nowMillis = 50L))
        assertEquals(80L, catchUpOccurrence(80L, nowMillis = 50L))
    }

    @Test
    fun onceUsesNextWallClockInZone() {
        val from = ZonedDateTime.of(2026, 8, 19, 15, 23, 3, 0, java.time.ZoneId.of("America/Chicago"))
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Once(from + 60_000),
            timeZone = "Central",
            runHour = 15,
            runMinute = 30,
            fromExclusiveMillis = from,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.of("America/Chicago"))
        assertEquals(19, nextTime.dayOfMonth)
        assertEquals(15, nextTime.hour)
        assertEquals(30, nextTime.minute)
    }

    @Test
    fun onceRollsToTomorrowWhenTodaysSlotPassed() {
        val from = ZonedDateTime.of(2026, 8, 19, 15, 31, 0, 0, java.time.ZoneId.of("America/Chicago"))
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Once(0L),
            timeZone = "America/Chicago",
            runHour = 15,
            runMinute = 30,
            fromExclusiveMillis = from,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.of("America/Chicago"))
        assertEquals(20, nextTime.dayOfMonth)
        assertEquals(15, nextTime.hour)
        assertEquals(30, nextTime.minute)
    }

    @Test
    fun onceDoesNotRepeatAfterFiring() {
        val from = ZonedDateTime.of(2026, 8, 19, 15, 23, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Once(from + 60_000),
            timeZone = "UTC",
            runHour = 15,
            runMinute = 30,
            fromExclusiveMillis = from,
            lastFiredAtMillis = from,
        )
        assertNull(next)
    }

    @Test
    fun weekdaysSkipWeekend() {
        val fridayEvening = ZonedDateTime.of(2026, 8, 21, 18, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Weekdays,
            timeZone = "UTC",
            runHour = 9,
            runMinute = 0,
            fromExclusiveMillis = fridayEvening,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC)
        assertEquals(java.time.DayOfWeek.MONDAY, nextTime.dayOfWeek)
        assertTrue(nextTime.dayOfMonth == 24)
    }

    @Test
    fun weeklyUsesIsoMonday() {
        val sunday = ZonedDateTime.of(2026, 8, 23, 18, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Weekly(1),
            timeZone = "UTC",
            runHour = 9,
            runMinute = 0,
            fromExclusiveMillis = sunday,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC)
        assertEquals(java.time.DayOfWeek.MONDAY, nextTime.dayOfWeek)
        assertEquals(24, nextTime.dayOfMonth)
    }

    @Test
    fun hourlyAdvancesToNextHourWhenSlotIsNotAfterFrom() {
        val from = ZonedDateTime.of(2026, 8, 19, 9, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Hourly,
            timeZone = "UTC",
            runHour = 0,
            runMinute = 0,
            fromExclusiveMillis = from,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC)
        assertEquals(10, nextTime.hour)
        assertEquals(0, nextTime.minute)
    }

    @Test
    fun invalidCronYieldsNoOccurrence() {
        val from = ZonedDateTime.of(2026, 8, 19, 9, 0, 0, 0, ZoneOffset.UTC)
        assertNull(nextCron("not a cron", from))
        assertNull(
            nextScheduleOccurrence(
                schedule = AutomationSchedule.Cron("not a cron"),
                timeZone = "UTC",
                runHour = 9,
                runMinute = 0,
                fromExclusiveMillis = from.toInstant().toEpochMilli(),
                lastFiredAtMillis = null,
            ),
        )
    }

    @Test
    fun cronQuarterHourStep() {
        val from = ZonedDateTime.of(2026, 8, 19, 9, 7, 0, 0, ZoneOffset.UTC)
        val next = nextCron("*/15 * * * *", from)!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(ZoneOffset.UTC)
        assertEquals(9, nextTime.hour)
        assertEquals(15, nextTime.minute)
    }

    @Test
    fun dstSpringForwardDailySlotMovesWithZoneRules() {
        val from = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, java.time.ZoneId.of("America/Chicago"))
            .toInstant().toEpochMilli()
        val next = nextScheduleOccurrence(
            schedule = AutomationSchedule.Daily,
            timeZone = "America/Chicago",
            runHour = 2,
            runMinute = 30,
            fromExclusiveMillis = from,
            lastFiredAtMillis = null,
        )!!
        val nextTime = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.of("America/Chicago"))
        assertTrue(nextTime.isAfter(java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.of("America/Chicago"))))
        assertEquals(30, nextTime.minute)
    }

    @Test
    fun idleSchedulerDoesNotPollWhenNothingIsArmed() {
        assertEquals(NoScheduledWakeDelayMillis, nextSchedulerWakeDelayMillis(emptyList(), nowMillis = 10L, inFlightIds = emptySet()))
        val paused = sampleDue(paused = true, nextRunAtMillis = 1L)
        assertEquals(NoScheduledWakeDelayMillis, nextSchedulerWakeDelayMillis(listOf(paused), 10L, emptySet()))
        val manual = sampleDue(schedule = AutomationSchedule.Manual, paused = false, nextRunAtMillis = 1L)
        assertEquals(NoScheduledWakeDelayMillis, nextSchedulerWakeDelayMillis(listOf(manual), 10L, emptySet()))
    }

    @Test
    fun dueAndWakeMathSkipInFlightAndManual() {
        val due = sampleDue(id = "a", nextRunAtMillis = 5L)
        val later = sampleDue(id = "b", nextRunAtMillis = 40L)
        assertEquals(listOf(due), dueScheduledAutomations(listOf(due, later), nowMillis = 10L, inFlightIds = emptySet()))
        assertEquals(emptyList(), dueScheduledAutomations(listOf(due), nowMillis = 10L, inFlightIds = setOf("a")))
        assertEquals(30L, nextSchedulerWakeDelayMillis(listOf(due, later), nowMillis = 10L, inFlightIds = setOf("a")))
        assertEquals(0L, nextSchedulerWakeDelayMillis(listOf(due), nowMillis = 10L, inFlightIds = emptySet()))
    }

    private fun sampleDue(
        id: String = "auto-1",
        paused: Boolean = false,
        schedule: AutomationSchedule = AutomationSchedule.Daily,
        nextRunAtMillis: Long? = 1L,
    ) = app.andy.model.Automation(
        id = id,
        projectId = "garden",
        title = "Triage",
        prompt = "look",
        schedule = schedule,
        timeZone = "UTC",
        launch = app.andy.model.AutomationLaunchSnapshot(agent = app.andy.model.AgentKind.Codex.name),
        paused = paused,
        createdAtMillis = 1,
        updatedAtMillis = 1,
        nextRunAtMillis = nextRunAtMillis,
    )
}
