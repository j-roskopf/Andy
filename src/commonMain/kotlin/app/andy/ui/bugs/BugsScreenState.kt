package app.andy.ui.bugs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.BugReport
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
    var playbackFrameIndex by mutableStateOf(0)
    var playbackStartFrameIndex by mutableStateOf(0)
    var isInspectingPlayback by mutableStateOf(false)
    var status by mutableStateOf("")
    var stepsPaneWidth by mutableStateOf(380f)
    var bugDetailsPaneWidth by mutableStateOf(320f)
    val expandedStepIds = mutableStateMapOf<String, Boolean>()

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
        expandedStepIds.clear()
    }

    fun seekPlayback(index: Int) {
        isReplaying = false
        isInspectingPlayback = true
        playbackFrameIndex = index
    }
}
