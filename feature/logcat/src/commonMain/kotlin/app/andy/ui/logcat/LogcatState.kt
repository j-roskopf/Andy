package app.andy.ui.logcat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.HostFileDocument
import app.andy.model.LogLevel
import app.andy.model.LogcatEntry

data class StampedLogcatEntry(
    val id: Long,
    val entry: LogcatEntry,
)

/** One-shot request to preview `path` at `line` (1-based) in an embedded [app.andy.HostCodeEditor]. */
data class CodePreviewState(
    val requestedPath: String,
    val line: Int,
    val loading: Boolean = true,
    val document: HostFileDocument? = null,
    val error: String? = null,
)

class LogcatState {
    private var nextEntryId = 0L
    var entries by mutableStateOf<List<StampedLogcatEntry>>(emptyList())
    var search by mutableStateOf("")

    fun appendBatch(batch: List<LogcatEntry>) {
        if (batch.isEmpty()) return
        val stamped = batch.map { entry ->
            StampedLogcatEntry(id = nextEntryId.also { nextEntryId++ }, entry = entry)
        }
        entries = (entries + stamped).takeLast(1200)
    }

    fun clearEntries() {
        entries = emptyList()
    }

    var live by mutableStateOf(true)
    val levels = mutableStateMapOf<LogLevel, Boolean>().also { map -> LogLevel.entries.forEach { map[it] = it != LogLevel.Verbose && it != LogLevel.Silent } }
    var lastSerial by mutableStateOf<String?>(null)
    var lastSearch by mutableStateOf<String?>(null)
    var lastLevels by mutableStateOf<Set<LogLevel>?>(null)
    var lastLive by mutableStateOf(true)
    var lastPackage by mutableStateOf<String?>(null)
    var codePreview by mutableStateOf<CodePreviewState?>(null)
}
