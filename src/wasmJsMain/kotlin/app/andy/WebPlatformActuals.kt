@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package app.andy

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.Clipboard
import app.andy.web.webPickFiles
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.JsString

@Composable
actual fun BugLogcatTextSurface(text: String, modifier: Modifier) {
    Text(text, modifier = modifier)
}

actual suspend fun Clipboard.setPlainText(text: String) {
    writeClipboardText(text)
}

private fun writeClipboardText(text: String): Unit =
    js("{ if (navigator.clipboard) navigator.clipboard.writeText(text); }")

actual suspend fun pickDirectory(initialDir: String?): String? = null

actual suspend fun pickFiles(initialDir: String?, allowMultiple: Boolean): List<String> =
    Json.decodeFromString(webPickFiles(allowMultiple).await<JsString>().toString())

actual fun downloadsDirectory(): String = "browser-downloads"

actual fun uniqueLocalPath(directory: String, fileName: String): String = fileName

actual fun Modifier.onExternalFileDrop(enabled: Boolean, onDrop: (paths: List<String>) -> Unit): Modifier = this

@Composable
actual fun HostCodeEditor(
    path: String,
    text: String,
    languageHint: String,
    modifier: Modifier,
    syntaxThemeId: String,
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
internal actual fun EditorSyntaxThemePreview(
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
