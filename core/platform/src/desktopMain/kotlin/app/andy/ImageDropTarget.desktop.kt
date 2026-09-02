package app.andy

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    val onFilesState = rememberUpdatedState(onFiles)
    val onDragActiveChangeState = rememberUpdatedState(onDragActiveChange)
    val target = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                if (event.awtTransferable.supportsImageDrop()) {
                    onDragActiveChangeState.value(true)
                }
            }

            override fun onEntered(event: DragAndDropEvent) {
                if (event.awtTransferable.supportsImageDrop()) {
                    onDragActiveChangeState.value(true)
                }
            }

            override fun onMoved(event: DragAndDropEvent) {
                if (event.awtTransferable.supportsImageDrop()) {
                    onDragActiveChangeState.value(true)
                }
            }

            override fun onExited(event: DragAndDropEvent) {
                onDragActiveChangeState.value(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                onDragActiveChangeState.value(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                val droppedImages = transferable.droppedImagePaths()
                onDragActiveChangeState.value(false)
                if (droppedImages.isEmpty()) return false
                onFilesState.value(droppedImages)
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
