package app.andy.domain

import app.andy.model.BugAction
import app.andy.model.BugReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BugPlaybackTest {
    @Test
    fun bugPlaybackMillisUsesFrameTimestampsWhenPresent() {
        val report = bugReport(videoFrameTimestampsMillis = listOf(1000L, 1100L, 1200L))
        assertEquals(1100L, bugPlaybackMillis(report, frameIndex = 1, frameCount = 3))
    }

    @Test
    fun bugPlaybackMillisInterpolatesVideoWindow() {
        val report = bugReport(videoStartedAtMillis = 1000L, videoEndedAtMillis = 2000L)
        assertEquals(1000L, bugPlaybackMillis(report, frameIndex = 0, frameCount = 3))
        assertEquals(1500L, bugPlaybackMillis(report, frameIndex = 1, frameCount = 3))
        assertEquals(2000L, bugPlaybackMillis(report, frameIndex = 2, frameCount = 3))
    }

    @Test
    fun activeBugActionIndexPicksNearestWithinWindow() {
        val actions = listOf(
            BugAction(id = "a", timestampMillis = 1000L, kind = "input", label = "Tap 10,10"),
            BugAction(id = "b", timestampMillis = 2000L, kind = "input", label = "Tap 20,20"),
            BugAction(id = "c", timestampMillis = 5000L, kind = "note", label = "note"),
        )
        assertEquals(1, activeBugActionIndex(actions, playbackMillis = 2050L))
        assertEquals(-1, activeBugActionIndex(actions, playbackMillis = 3500L))
    }

    @Test
    fun parseBugActionPointReadsCoordinatesFromInputActions() {
        assertEquals(
            120 to 340,
            parseBugActionPoint(BugAction(id = "1", timestampMillis = 1L, kind = "input", label = "Tap", detail = "120,340 · clickable")),
        )
        assertNull(parseBugActionPoint(BugAction(id = "2", timestampMillis = 1L, kind = "note", label = "120,340")))
    }

    @Test
    fun activeBugPointerEventReturnsProgressForNearbyInput() {
        val actions = listOf(
            BugAction(id = "a", timestampMillis = 1000L, kind = "input", label = "Tap 50,60"),
        )
        val event = activeBugPointerEvent(actions, playbackMillis = 1300L)
        assertEquals(50, event?.x)
        assertEquals(60, event?.y)
        assertEquals(300f / BugPointerHighlightMillis, event?.progress)
        assertNull(activeBugPointerEvent(actions, playbackMillis = 3000L))
    }

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
        capturedAtMillis = 3000L,
        windowStartedAtMillis = 1000L,
        windowEndedAtMillis = 3000L,
        actions = emptyList(),
        artifacts = emptyList(),
        videoStartedAtMillis = videoStartedAtMillis,
        videoEndedAtMillis = videoEndedAtMillis,
        videoFrameTimestampsMillis = videoFrameTimestampsMillis,
    )
}
