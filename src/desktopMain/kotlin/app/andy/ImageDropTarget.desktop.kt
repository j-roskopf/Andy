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
import java.awt.datatransfer.DataFlavor
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

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
                val files = runCatching {
                    transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                }.getOrNull().orEmpty()
                    .filterIsInstance<File>()
                    .filter { file -> file.isFile && file.isSupportedImageFile() }
                    .map { it.absolutePath }
                val droppedImages = files.ifEmpty {
                    if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        (runCatching { transferable.getTransferData(DataFlavor.imageFlavor) as? Image }
                            .getOrNull()
                            ?.persistDroppedImage())
                            ?.let(::listOf)
                            .orEmpty()
                    } else {
                        emptyList()
                    }
                }
                if (droppedImages.isEmpty()) return false
                onFiles(droppedImages)
                onDragActiveChange(false)
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                event.awtTransferable.isDataFlavorSupported(DataFlavor.imageFlavor)
        },
        target = target,
    )
}

private fun File.isSupportedImageFile(): Boolean = extension.lowercase() in setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "svg", "heic", "heif",
)

private fun Image.persistDroppedImage(): String? = runCatching {
    val buffered = if (this is BufferedImage) this else {
        BufferedImage(getWidth(null), getHeight(null), BufferedImage.TYPE_INT_ARGB).also { converted ->
            val graphics = converted.createGraphics()
            try {
                graphics.drawImage(this, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }
    }
    val directory = File(System.getProperty("user.home"), ".andy/agent-images").apply { mkdirs() }
    val target = File.createTempFile("dropped-", ".png", directory)
    check(ImageIO.write(buffered, "png", target)) { "PNG writer unavailable" }
    target.absolutePath
}.getOrNull()
