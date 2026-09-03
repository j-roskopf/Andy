package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult

/**
 * Opens http(s) links in the composer on ⌘/Ctrl-click or middle-click, and shows a
 * hand cursor while hovering them. Plain primary clicks still place the caret.
 */
@Composable
fun Modifier.composerOpenLinks(
    text: String,
    layoutResult: TextLayoutResult?,
    onOpenUrl: ((String) -> Unit)? = null,
): Modifier {
    val uriHandler = LocalUriHandler.current
    val openUrl by rememberUpdatedState(onOpenUrl ?: { url ->
        runCatching { uriHandler.openUri(url) }
    })
    val layout by rememberUpdatedState(layoutResult)
    val links = remember(text) { findComposerLinks(text) }
    var hoveringLink by remember(text) { mutableStateOf(false) }

    return this
        .pointerInput(text, links) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: continue
                    val result = layout
                    val offset = result?.getOffsetForPosition(change.position)
                    val link = offset?.let { off ->
                        links.firstOrNull { link ->
                            off >= link.start && off < link.end
                        }
                    }

                    when (event.type) {
                        PointerEventType.Move, PointerEventType.Enter -> {
                            hoveringLink = link != null
                        }
                        PointerEventType.Exit -> hoveringLink = false
                        PointerEventType.Press -> {
                            if (link == null) continue
                            val open = event.buttons.isTertiaryPressed ||
                                (
                                    event.buttons.isPrimaryPressed &&
                                        (event.keyboardModifiers.isMetaPressed ||
                                            event.keyboardModifiers.isCtrlPressed)
                                    )
                            if (!open) continue
                            change.consume()
                            openUrl(link.url)
                        }
                    }
                }
            }
        }
        .then(if (hoveringLink) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
}
