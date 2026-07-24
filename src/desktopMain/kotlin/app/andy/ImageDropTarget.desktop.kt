package app.andy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.onImageFilesDropped(
    onFiles: (List<String>) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier {
    val target = remember(onFiles, onDragActiveChange) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                onDragActiveChange(true)
            }

            override fun onEntered(event: DragAndDropEvent) {
                onDragActiveChange(true)
            }

            override fun onExited(event: DragAndDropEvent) {
                onDragActiveChange(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                onDragActiveChange(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                val droppedImages = transferable.droppedImagePaths()
                if (droppedImages.isEmpty()) return false
                onFiles(droppedImages)
                onDragActiveChange(false)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.awtTransferable.supportsImageDrop()
        },
        target = target,
    )
}
