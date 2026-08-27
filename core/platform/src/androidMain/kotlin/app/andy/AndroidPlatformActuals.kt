package app.andy

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.Clipboard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
actual fun BugLogcatTextSurface(text: String, modifier: Modifier) {
    Text(text, modifier = modifier)
}

actual suspend fun Clipboard.setPlainText(text: String) {
    // Android host shell does not wire desktop clipboard bridges yet.
}

actual suspend fun Clipboard.readPlainText(): String? = null

actual suspend fun pickDirectory(initialDir: String?): String? = null

actual suspend fun pickFiles(initialDir: String?, allowMultiple: Boolean): List<String> = emptyList()

actual suspend fun pickSavePath(suggestedName: String, initialDir: String?): String? = null

actual fun downloadsDirectory(): String = ""

actual fun uniqueLocalPath(directory: String, fileName: String): String = fileName

actual fun Modifier.onExternalFileDrop(enabled: Boolean, onDrop: (paths: List<String>) -> Unit): Modifier = this

@Composable
actual fun HostCodeEditor(
    path: String,
    text: String,
    languageHint: String,
    modifier: Modifier,
    syntaxThemeId: String,
    initialLine: Int?,
    onTextChange: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit,
    onSearchAll: () -> Unit,
    onSearchNames: () -> Unit,
    onSearchContents: () -> Unit,
) {
    Box(modifier) { Text(text) }
}

@Composable
actual fun EditorSyntaxThemePreview(
    syntaxThemeId: String,
    modifier: Modifier,
) {
    Text(EditorSyntaxThemeSample, modifier = modifier)
}

@Composable
actual fun Modifier.onImageFilesDropped(
    onFiles: (List<String>) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
): Modifier = this

actual fun Modifier.horizontalResizeCursor(): Modifier = this

actual fun Modifier.verticalResizeCursor(): Modifier = this

actual fun loadImageBitmap(path: String): ImageBitmap? = null

actual fun loadImageBitmap(bytes: ByteArray): ImageBitmap? = null

actual suspend fun fetchRemoteBytes(url: String): ByteArray? = null

actual suspend fun readClipboardImagePaths(): List<String> = emptyList()

private val displayDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault())

actual fun hostTimeZoneId(): String = ZoneId.systemDefault().id

actual fun formatDisplayDateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "-"
    return displayDateTimeFormatter.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
    )
}
