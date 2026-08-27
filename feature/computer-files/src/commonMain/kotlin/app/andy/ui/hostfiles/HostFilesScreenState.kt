package app.andy.ui.hostfiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import app.andy.model.HostFileEntry
import app.andy.model.HostIndexStatus
import app.andy.model.HostSearchMode
import app.andy.model.HostSearchResult
import app.andy.service.HostFileService

internal data class HostEditorTab(
    val path: String,
    val content: String,
    val savedContent: String,
    val modifiedMillis: Long,
    val sizeBytes: Long,
    val languageHint: String,
    val message: String = "",
) {
    val dirty: Boolean get() = content != savedContent
}

internal class HostFilesScreenState(
    val service: HostFileService,
) {
    var message by mutableStateOf("")
    var searchQuery by mutableStateOf("")
    var searchMode by mutableStateOf(HostSearchMode.Combined)
    var searchResults by mutableStateOf<List<HostSearchResult>>(emptyList())
    var tabs by mutableStateOf<List<HostEditorTab>>(emptyList())
    var activePath by mutableStateOf<String?>(null)
    var conflictTab by mutableStateOf<HostEditorTab?>(null)
    val statuses = mutableStateMapOf<String, HostIndexStatus>()
    val treeChildren = mutableStateMapOf<String, List<HostFileEntry>>()
    val expandedPaths = mutableStateMapOf<String, Boolean>()
    val searchFocusRequester = FocusRequester()
    var pendingTreeScrollPath by mutableStateOf<String?>(null)

    fun closeActiveTab() {
        val path = activePath ?: return
        val nextTabs = tabs.filterNot { it.path == path }
        tabs = nextTabs
        activePath = nextTabs.lastOrNull()?.path
    }

    fun updateEditorTextForPath(path: String, value: String): Boolean {
        if (path != activePath) {
            message = "Edit ignored: editor event did not match the visible file."
            return false
        }
        if (tabs.none { it.path == path }) {
            message = "Edit ignored: file tab is no longer open."
            return false
        }
        tabs = tabs.map { if (it.path == path) it.copy(content = value) else it }
        return true
    }
}
