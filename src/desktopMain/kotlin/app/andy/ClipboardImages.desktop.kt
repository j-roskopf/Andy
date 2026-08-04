package app.andy

import java.awt.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun readClipboardImagePaths(): List<String> = withContext(Dispatchers.IO) {
  val transferable = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@withContext emptyList()
  transferable.droppedImagePaths()
}
