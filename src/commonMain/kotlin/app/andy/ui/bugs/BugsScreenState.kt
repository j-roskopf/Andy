package app.andy.ui.bugs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.domain.InvestigationTimelineFilters
import app.andy.model.BugReport
import app.andy.model.InvestigationTimeline
import app.andy.service.BugService
import app.andy.service.MirrorFrame

internal class BugsScreenState(
    val bugs: BugService,
) {
    var reports by mutableStateOf<List<BugReport>>(emptyList())
    var selectedId by mutableStateOf<String?>(null)
    var selected by mutableStateOf<BugReport?>(null)
    var logcat by mutableStateOf("")
    var selectedTab by mutableStateOf("Details")
    var playbackFrame by mutableStateOf<MirrorFrame?>(null)
    var playbackRunId by mutableStateOf(0)
    var isReplaying by mutableStateOf(false)
    var playbackFrameCount by mutableStateOf(0)
    var isVideoLoading by mutableStateOf(false)
    var playbackFrameIndex by mutableStateOf(0)
    var playbackStartFrameIndex by mutableStateOf(0)
    var isInspectingPlayback by mutableStateOf(false)
    var status by mutableStateOf("")
    var timelinePaneWidth by mutableStateOf(280f)
    var bugDetailsPaneWidth by mutableStateOf(240f)
    val expandedEventIds = mutableStateMapOf<String, Boolean>()

    /** `timeline.json` for the selected report, or null for v1 reports (falls back to migration). */
    var timeline by mutableStateOf<InvestigationTimeline?>(null)
    var timelineFilters by mutableStateOf(InvestigationTimelineFilters())

    /**
     * Explicit event pick from clicking a timeline row. Cleared whenever the user scrubs the
     * video directly so the detail pane goes back to following the nearest event under playback.
     */
    var selectedEventId by mutableStateOf<String?>(null)

    fun toggleReplay() {
        if (isReplaying) {
            isReplaying = false
        } else {
            isInspectingPlayback = true
            playbackStartFrameIndex = playbackFrameIndex
            isReplaying = true
            playbackRunId++
        }
    }

    fun resetPlaybackForSelection() {
        playbackFrame = null
        playbackFrameIndex = 0
        playbackStartFrameIndex = 0
        isInspectingPlayback = false
        isReplaying = false
        playbackFrameCount = 0
        isVideoLoading = true
        expandedEventIds.clear()
        timeline = null
        timelineFilters = InvestigationTimelineFilters()
        selectedEventId = null
    }

    /** Slider/keyboard scrubbing — follows the nearest event under the new playback position. */
    fun seekPlayback(index: Int) {
        isReplaying = false
        isInspectingPlayback = true
        playbackFrameIndex = index
        selectedEventId = null
    }

    /** Clicking a timeline row — pins the detail pane to [eventId] and jumps the video there. */
    fun seekPlaybackToEvent(index: Int, eventId: String) {
        isReplaying = false
        isInspectingPlayback = true
        playbackFrameIndex = index
        selectedEventId = eventId
    }
}
