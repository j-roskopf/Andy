package app.andy.domain

import app.andy.model.BugReport
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationTimeline
import kotlin.test.Test
import kotlin.test.assertEquals

class InvestigationPlaybackTest {
    @Test
    fun activeInvestigationEventIndexPicksNearestWithinWindow() {
        val events = listOf(
            event("a", 1000L, InvestigationEventKind.Action),
            event("b", 2000L, InvestigationEventKind.NetworkExchange),
            event("c", 5000L, InvestigationEventKind.LogLine),
        )
        assertEquals(1, activeInvestigationEventIndex(events, playbackMillis = 2050L))
        assertEquals(-1, activeInvestigationEventIndex(events, playbackMillis = 3500L))
    }

    @Test
    fun filtersHideNonMatchingKinds() {
        val timeline = InvestigationTimeline(
            originMillis = 0L,
            endedAtMillis = 10L,
            events = listOf(
                event("a", 1L, InvestigationEventKind.Action),
                event("n", 2L, InvestigationEventKind.NetworkExchange),
                event("c", 3L, InvestigationEventKind.Crash),
            ),
        )
        val filters = InvestigationTimelineFilters(kinds = setOf(InvestigationEventKind.Crash))
        assertEquals(listOf("c"), timeline.filtered(filters).map { it.id })
    }

    @Test
    fun selectEvidenceWindowKeepsFocusedNeighbors() {
        val timeline = InvestigationTimeline(
            originMillis = 0L,
            endedAtMillis = 10_000L,
            events = (0..20).map { i ->
                event("e$i", i * 100L, InvestigationEventKind.LogLine)
            },
        )
        val selected = timeline.selectEvidenceWindow(
            focusedEventId = "e10",
            centerMillis = 0L,
            windowRadiusMillis = 250L,
            maxEvents = 5,
        )
        assertEquals(listOf("e8", "e9", "e10", "e11", "e12"), selected.map { it.id })
    }

    @Test
    fun nearestBugFrameIndexPrefersExactFrameTimestamps() {
        val report = bugReport(videoFrameTimestampsMillis = listOf(1_000L, 1_100L, 1_300L))
        assertEquals(0, nearestBugFrameIndex(report, targetMillis = 1_020L, frameCount = 3))
        assertEquals(1, nearestBugFrameIndex(report, targetMillis = 1_150L, frameCount = 3))
        assertEquals(2, nearestBugFrameIndex(report, targetMillis = 5_000L, frameCount = 3))
    }

    @Test
    fun nearestBugFrameIndexFallsBackToLinearSpanWithoutTimestamps() {
        val report = bugReport(videoStartedAtMillis = 0L, videoEndedAtMillis = 1_000L)
        assertEquals(0, nearestBugFrameIndex(report, targetMillis = -100L, frameCount = 5))
        assertEquals(2, nearestBugFrameIndex(report, targetMillis = 500L, frameCount = 5))
        assertEquals(4, nearestBugFrameIndex(report, targetMillis = 10_000L, frameCount = 5))
    }

    private fun event(id: String, at: Long, kind: InvestigationEventKind) = InvestigationEvent(
        id = id,
        atMillis = at,
        kind = kind,
        summary = id,
    )

    private fun bugReport(
        videoFrameTimestampsMillis: List<Long> = emptyList(),
        videoStartedAtMillis: Long? = null,
        videoEndedAtMillis: Long? = null,
    ) = BugReport(
        id = "bug-1",
        title = "Test",
        notes = "",
        deviceSerial = "serial",
        deviceModel = null,
        apiLevel = null,
        abi = null,
        resolution = null,
        capturedAtMillis = 1_000L,
        windowStartedAtMillis = 0L,
        windowEndedAtMillis = 1_000L,
        actions = emptyList(),
        artifacts = emptyList(),
        videoStartedAtMillis = videoStartedAtMillis,
        videoEndedAtMillis = videoEndedAtMillis,
        videoFrameTimestampsMillis = videoFrameTimestampsMillis,
    )
}
