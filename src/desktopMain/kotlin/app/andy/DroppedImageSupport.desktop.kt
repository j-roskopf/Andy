package app.andy

import java.awt.Component
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

internal fun File.isSupportedImageFile(): Boolean = extension.lowercase() in supportedImageExtensions

internal fun Image.persistDroppedImage(): String? = runCatching {
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

internal fun Transferable.supportsImageDrop(): Boolean =
    isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
        isDataFlavorSupported(DataFlavor.imageFlavor) ||
        isDataFlavorSupported(DataFlavor.stringFlavor)

internal fun Transferable.droppedImagePaths(): List<String> {
    val files = runCatching {
        getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    }.getOrNull().orEmpty()
        .filterIsInstance<File>()
        .filter { file -> file.isFile && file.isSupportedImageFile() }
        .map { it.absolutePath }
    if (files.isNotEmpty()) return files

    if (isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val persisted = runCatching { getTransferData(DataFlavor.imageFlavor) as? Image }
            .getOrNull()
            ?.persistDroppedImage()
        if (persisted != null) return listOf(persisted)
    }

    if (isDataFlavorSupported(DataFlavor.stringFlavor)) {
        val uriPaths = runCatching { getTransferData(DataFlavor.stringFlavor) as? String }
            .getOrNull()
            .orEmpty()
            .lineSequence()
            .map { it.trim().removePrefix("file://") }
            .filter { it.isNotBlank() }
            .map { File(it) }
            .filter { file -> file.isFile && file.isSupportedImageFile() }
            .map { it.absolutePath }
            .toList()
        if (uriPaths.isNotEmpty()) return uriPaths
    }

    return emptyList()
}

/** Installs an AWT drop target that accepts desktop image files and bitmap drags. */
internal fun Component.installImageDropTarget(
    onFiles: (List<String>) -> Unit,
    onDragActiveChange: ((Boolean) -> Unit)? = null,
): DropTarget {
    val listener = object : DropTargetAdapter() {
        private fun handleDrag(event: DropTargetDragEvent) {
            if (event.transferable.supportsImageDrop()) {
                event.acceptDrag(DnDConstants.ACTION_COPY)
                onDragActiveChange?.invoke(true)
            } else {
                event.rejectDrag()
                onDragActiveChange?.invoke(false)
            }
        }

        override fun dragEnter(event: DropTargetDragEvent) = handleDrag(event)

        override fun dragOver(event: DropTargetDragEvent) = handleDrag(event)

        override fun dragExit(event: java.awt.dnd.DropTargetEvent) {
            onDragActiveChange?.invoke(false)
        }

        override fun drop(event: DropTargetDropEvent) {
            onDragActiveChange?.invoke(false)
            if (!event.transferable.supportsImageDrop()) {
                event.rejectDrop()
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val paths = event.transferable.droppedImagePaths()
            if (paths.isEmpty()) {
                event.dropComplete(false)
                return
            }
            onFiles(paths)
            event.dropComplete(true)
        }
    }
    return DropTarget(this, DnDConstants.ACTION_COPY, listener, true)
}

private val supportedImageExtensions = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "svg", "heic", "heif",
)
