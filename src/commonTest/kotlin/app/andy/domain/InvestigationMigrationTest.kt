package app.andy.domain

import app.andy.model.BugAction
import app.andy.model.BugReport
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationMigrationTest {
    @Test
    fun migrateV1ActionsPreservesOrderAndTimestamps() {
        val report = bugReport(
            actions = listOf(
                BugAction("b", 2000L, "input", "Tap 1,1"),
                BugAction("a", 1000L, "nav", "Back"),
                BugAction("c", 3000L, "note", "checkpoint"),
            ),
        )
        val timeline = migrateV1BugReportToTimeline(report)
        assertEquals(listOf(1000L, 2000L, 3000L), timeline.events.map(InvestigationEvent::atMillis))
        assertEquals(
            listOf(
                InvestigationEventKind.Action,
                InvestigationEventKind.Action,
                InvestigationEventKind.UserMarker,
            ),
            timeline.events.map(InvestigationEvent::kind),
        )
        assertEquals("a", timeline.events.first().id)
    }

    @Test
    fun migrateV1LogcatIsApproximatelyTimed() {
        val report = bugReport(
            windowStartedAtMillis = 0L,
            windowEndedAtMillis = 1000L,
            capturedAtMillis = 1000L,
        )
        val timeline = migrateV1BugReportToTimeline(report, logcatLines = listOf("line-a", "line-b", "line-c"))
        assertEquals(3, timeline.events.size)
        assertTrue(timeline.events.all { it.inline?.approximatelyTimed == true })
        assertEquals(listOf(0L, 500L, 1000L), timeline.events.map(InvestigationEvent::atMillis))
    }

    @Test
    fun investigationTimelineForPrefersLoadedTimeline() {
        val report = bugReport()
        val loaded = InvestigationTimeline(
            originMillis = 10L,
            endedAtMillis = 20L,
            events = listOf(
                InvestigationEvent(
                    id = "e1",
                    atMillis = 15L,
                    kind = InvestigationEventKind.Crash,
                    summary = "boom",
                ),
            ),
        )
        assertEquals(loaded, investigationTimelineFor(report, loadedTimeline = loaded))
        assertEquals(0, investigationTimelineFor(report, loadedTimeline = null).events.size)
    }

    @Test
    fun roundTripActionEventsThroughLegacyAdapter() {
        val action = BugAction("x", 42L, "input", "Tap", "10,20")
        val event = action.toInvestigationEvent()
        assertEquals(InvestigationEventKind.Action, event.kind)
        val back = event.toBugActionOrNull()
        assertEquals(action.id, back?.id)
        assertEquals(action.timestampMillis, back?.timestampMillis)
        assertEquals(action.label, back?.label)
    }

    private fun bugReport(
        actions: List<BugAction> = emptyList(),
        windowStartedAtMillis: Long = 1000L,
        windowEndedAtMillis: Long = 3000L,
        capturedAtMillis: Long = 3000L,
    ) = BugReport(
        id = "bug-1",
        title = "Test",
        notes = "",
        deviceSerial = "serial",
        deviceModel = null,
        apiLevel = null,
        abi = null,
        resolution = null,
        capturedAtMillis = capturedAtMillis,
        windowStartedAtMillis = windowStartedAtMillis,
        windowEndedAtMillis = windowEndedAtMillis,
        actions = actions,
        artifacts = emptyList(),
    )
}
