package app.andy

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class)
actual suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(StringSelection(text)))
}

actual suspend fun Clipboard.readPlainText(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val transferable = Toolkit.getDefaultToolkit().systemClipboard.getContents(null) ?: return@withContext null
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return@withContext null
        (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
