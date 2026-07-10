package app.andy

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.ExperimentalComposeUiApi
import java.io.File
import java.net.URI

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.onExternalFileDrop(
    enabled: Boolean,
    onDrop: (paths: List<String>) -> Unit,
): Modifier = composed {
    val target = remember(onDrop) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val paths = event.filePaths()
                if (paths.isEmpty()) return false
                onDrop(paths)
                return true
            }
        }
    }
    if (!enabled) return@composed this
    dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.dragData() is DragData.FilesList
        },
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.filePaths(): List<String> {
    val data = dragData() as? DragData.FilesList ?: return emptyList()
    return data.readFiles().mapNotNull { uriString ->
        runCatching {
            val uri = URI(uriString)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                File(uri).absolutePath
            } else {
                uriString
            }
        }.getOrNull()
    }
}
