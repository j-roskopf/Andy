package app.andy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.andy.mergeChatImagePaths
import app.andy.model.shouldAttachLargeText
import app.andy.pickImageFiles
import app.andy.readClipboardImagePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ChatImageAttachButton(
    onImagesAttached: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    ComposerChip(
        text = "+",
        selected = false,
        enabled = enabled,
        showChevron = false,
        modifier = modifier,
        onClick = { scope.launch { attachImagesFromPicker(onImagesAttached) } },
    )
}

suspend fun attachImagesFromPicker(onImagesAttached: (List<String>) -> Unit) {
    val picked = pickImageFiles()
    if (picked.isNotEmpty()) onImagesAttached(picked)
}

/**
 * Intercepts Cmd/Ctrl+V before the TextField paste runs. Images take priority; otherwise plain
 * text is routed to [onLargeTextPaste] or [onSmallTextPaste]. Returns true (consumes the event)
 * once the shortcut is recognized — clipboard work runs on [scope].
 */
fun Modifier.onChatComposerPaste(
    scope: CoroutineScope,
    onImagesAttached: (List<String>) -> Unit,
    onSmallTextPaste: (String) -> Unit,
    onLargeTextPaste: (String) -> Unit,
    readClipboardText: suspend () -> String? = { null },
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.key != Key.V || (!event.isMetaPressed && !event.isCtrlPressed)) return@onPreviewKeyEvent false
    scope.launch {
        val pastedImages = readClipboardImagePaths()
        if (pastedImages.isNotEmpty()) {
            onImagesAttached(pastedImages)
            return@launch
        }
        val text = readClipboardText()?.takeIf { it.isNotEmpty() } ?: return@launch
        if (shouldAttachLargeText(text)) {
            onLargeTextPaste(text)
        } else {
            onSmallTextPaste(text)
        }
    }
    true
}

/**
 * Image-only paste hook for simple text fields. Does not consume Cmd/Ctrl+V, so plain-text
 * paste still reaches the TextField. Prefer [onChatComposerPaste] when staging large text.
 */
fun Modifier.onChatImagePaste(
    scope: CoroutineScope,
    onImagesAttached: (List<String>) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.key != Key.V || (!event.isMetaPressed && !event.isCtrlPressed)) return@onPreviewKeyEvent false
    scope.launch {
        val pasted = readClipboardImagePaths()
        if (pasted.isNotEmpty()) onImagesAttached(pasted)
    }
    false
}

fun attachChatImages(existing: List<String>, added: List<String>): List<String> =
    existing.mergeChatImagePaths(added)
