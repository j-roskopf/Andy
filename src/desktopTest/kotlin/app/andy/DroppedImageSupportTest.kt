package app.andy

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DroppedImageSupportTest {
    @Test
    fun acceptsFileListAndImageFlavors() {
        val image = File.createTempFile("andy-drop-test", ".png").apply { deleteOnExit() }
        val transferable = FakeTransferable(
            flavors = listOf(DataFlavor.javaFileListFlavor),
            values = mapOf(DataFlavor.javaFileListFlavor to listOf(image)),
        )
        assertTrue(transferable.supportsImageDrop())
        assertEquals(listOf(image.absolutePath), transferable.droppedImagePaths())
    }

    @Test
    fun ignoresNonImageFiles() {
        val transferable = FakeTransferable(
            flavors = listOf(DataFlavor.javaFileListFlavor),
            values = mapOf(DataFlavor.javaFileListFlavor to listOf(File("/tmp/notes.txt"))),
        )
        assertTrue(transferable.supportsImageDrop())
        assertTrue(transferable.droppedImagePaths().isEmpty())
    }

    @Test
    fun stringFlavorWithoutImagePathsReturnsEmpty() {
        val transferable = FakeTransferable(
            flavors = listOf(DataFlavor.stringFlavor),
            values = mapOf(DataFlavor.stringFlavor to "hello"),
        )
        assertTrue(transferable.supportsImageDrop())
        assertTrue(transferable.droppedImagePaths().isEmpty())
    }
}

private class FakeTransferable(
    private val flavors: List<DataFlavor>,
    private val values: Map<DataFlavor, Any>,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors

    override fun getTransferData(flavor: DataFlavor): Any =
        values[flavor] ?: throw UnsupportedFlavorException(flavor)
}
