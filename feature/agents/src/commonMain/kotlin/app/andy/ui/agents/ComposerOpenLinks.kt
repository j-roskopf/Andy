package app.andy.ui.agents

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextLayoutResult
import app.andy.ui.theme.AndySpace

/**
 * Opens http(s) links in the composer on ⌘/Ctrl-click or middle-click, and shows a
 * hand cursor while hovering them. Plain primary clicks still place the caret.
 *
 * Coordinates are translated from the outer field into inner text layout space using
 * [contentPadding] (defaulting to the shared field decoration padding).
 */
@Composable
fun Modifier.composerOpenLinks(
    text: String,
    layoutResult: TextLayoutResult?,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = AndySpace.Space2,
        vertical = AndySpace.Space2,
    ),
    onOpenUrl: ((String) -> Unit)? = null,
): Modifier {
    val uriHandler = LocalUriHandler.current
    val openUrl by rememberUpdatedState(onOpenUrl ?: { url ->
        runCatching { uriHandler.openUri(url) }
    })
    val layout by rememberUpdatedState(layoutResult)
    val links = remember(text) { findComposerLinks(text) }
    var hoveringLink by remember(text) { mutableStateOf(false) }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val padStartPx = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() }
    val padTopPx = with(density) { contentPadding.calculateTopPadding().toPx() }

    return this
        .pointerInput(text, links, padStartPx, padTopPx) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: continue
                    val result = layout
                    val textX = change.position.x - padStartPx
                    val textY = change.position.y - padTopPx
                    val link = if (result != null &&
                        textX >= 0f && textY >= 0f &&
                        textX < result.size.width && textY < result.size.height
                    ) {
                        val offset = result.getOffsetForPosition(Offset(textX, textY))
                        links.firstOrNull { link ->
                            offset >= link.start && offset < link.end
                        }
                    } else {
                        null
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
