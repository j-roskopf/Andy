package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

expect suspend fun Clipboard.setPlainText(text: String)

expect suspend fun Clipboard.readPlainText(): String?

@Composable
fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text: String ->
            scope.launch { clipboard.setPlainText(text) }
        }
    }
}

/** Reads plain text from the clipboard on a background coroutine. */
@Composable
fun rememberReadClipboardText(): (onText: (String) -> Unit) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { onText ->
            scope.launch {
                clipboard.readPlainText()?.takeIf { it.isNotEmpty() }?.let(onText)
            }
        }
    }
}
