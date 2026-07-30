package app.andy.ui.bugs

import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationSelectionTest {

    private fun events(vararg atMillis: Long): List<InvestigationEvent> = atMillis.mapIndexed { index, millis ->
        InvestigationEvent(
            id = "event-$index",
            atMillis = millis,
            kind = InvestigationEventKind.Action,
            summary = "event $index",
        )
    }

    @Test
    fun selectionCoversOnlyEventsInsideTheWindow() {
        val selected = investigationSelectionAround(
            events = events(0L, 5_000L, 10_000L, 90_000L),
            centerMillis = 5_000L,
            radiusMillis = 10_000L,
        )
        assertEquals(listOf("event-0", "event-1", "event-2"), selected.map { it.id })
    }

    @Test
    fun selectionIsSymmetricAroundTheScrubPosition() {
        val selected = investigationSelectionAround(
            events = events(0L, 20_000L),
            centerMillis = 10_000L,
            radiusMillis = 10_000L,
        )
        assertEquals(2, selected.size, "both edges of the window are inclusive")
    }

    @Test
    fun oversizedWindowsKeepTheClosestEventsInTimelineOrder() {
        val timeline = events(*LongArray(100) { it * 1_000L })
        val selected = investigationSelectionAround(
            events = timeline,
            centerMillis = 50_000L,
            radiusMillis = 100_000L,
            limit = 5,
        )
        assertEquals(5, selected.size)
        assertEquals(listOf("event-48", "event-49", "event-50", "event-51", "event-52"), selected.map { it.id })
        assertTrue(selected.zipWithNext().all { (a, b) -> a.atMillis <= b.atMillis })
    }

    @Test
    fun emptyWindowsProduceNoSelection() {
        assertTrue(investigationSelectionAround(events(0L), centerMillis = 500_000L, radiusMillis = 1_000L).isEmpty())
    }

    @Test
    fun summaryCountsEventsByKind() {
        val mixed = listOf(
            InvestigationEvent("a", 0L, kind = InvestigationEventKind.Action, summary = "a"),
            InvestigationEvent("b", 1L, kind = InvestigationEventKind.Crash, summary = "b"),
            InvestigationEvent("c", 2L, kind = InvestigationEventKind.Action, summary = "c"),
        )
        val summary = investigationSelectionSummary(mixed)
        assertTrue(summary.startsWith("3 event(s): "), "summary was: $summary")
        assertTrue(summary.contains("Action×2"))
        assertTrue(summary.contains("Crash×1"))
    }

    @Test
    fun emptySelectionsSaySo() {
        assertEquals("No events in the selected window.", investigationSelectionSummary(emptyList()))
    }
}
