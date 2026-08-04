package app.andy.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.mergeChatImagePaths
import app.andy.pickImageFiles
import app.andy.readClipboardImagePaths
import app.andy.ui.theme.AndyLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ChatImageAttachButton(
    onImagesAttached: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = { scope.launch { attachImagesFromPicker(onImagesAttached) } },
        enabled = enabled,
        modifier = modifier.height(AndyLayout.ControlHeightMd),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text("attach", fontSize = 11.sp)
    }
}

internal suspend fun attachImagesFromPicker(onImagesAttached: (List<String>) -> Unit) {
    val picked = pickImageFiles()
    if (picked.isNotEmpty()) onImagesAttached(picked)
}

internal fun Modifier.onChatImagePaste(
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

internal fun attachChatImages(existing: List<String>, added: List<String>): List<String> =
    existing.mergeChatImagePaths(added)
