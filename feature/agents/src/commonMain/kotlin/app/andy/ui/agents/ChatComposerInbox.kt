package app.andy.ui.agents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.andy.model.AgentAttachment
import app.andy.model.shouldAttachLargeText
import app.andy.ui.components.attachChatImages
import app.andy.ui.components.insertTextAtCursor

/**
 * Cross-pane attachments destined for whichever chat composer is currently accepting
 * input (new-task composer or an open transcript follow-up).
 */
data class ChatComposerAttachment(
    val imagePaths: List<String> = emptyList(),
    val text: String = "",
    val attachments: List<AgentAttachment> = emptyList(),
)

class ChatComposerInbox {
    private val sinks = mutableListOf<(ChatComposerAttachment) -> Unit>()
    private val pending = ArrayDeque<ChatComposerAttachment>()

    fun offer(item: ChatComposerAttachment) {
        if (item.imagePaths.isEmpty() && item.text.isBlank() && item.attachments.isEmpty()) return
        val sink = sinks.lastOrNull()
        if (sink != null) {
            sink(item)
        } else {
            pending.addLast(item)
        }
    }

    fun register(sink: (ChatComposerAttachment) -> Unit): () -> Unit {
        sinks.add(sink)
        while (pending.isNotEmpty()) {
            sink(pending.removeFirst())
        }
        return {
            sinks.remove(sink)
        }
    }
}

val LocalChatComposerInbox = staticCompositionLocalOf { ChatComposerInbox() }

@Composable
fun CollectChatComposerInbox(
    active: Boolean,
    onAttachment: (ChatComposerAttachment) -> Unit,
) {
    val inbox = LocalChatComposerInbox.current
    val latest = rememberUpdatedState(onAttachment)
    DisposableEffect(active, inbox) {
        if (!active) {
            return@DisposableEffect onDispose { }
        }
        val unregister = inbox.register { latest.value(it) }
        onDispose(unregister)
    }
}

data class ChatComposerApplyResult(
    val text: TextFieldValue,
    val images: List<String>,
    val attachments: List<AgentAttachment>,
    /** Large pasted prose that must be staged asynchronously by the composer. */
    val largeTextToStage: String? = null,
)

fun applyChatComposerAttachment(
    currentText: TextFieldValue,
    currentImages: List<String>,
    currentAttachments: List<AgentAttachment> = emptyList(),
    item: ChatComposerAttachment,
): ChatComposerApplyResult {
    val images = attachChatImages(currentImages, item.imagePaths)
    val attachments = currentAttachments + item.attachments
    val addition = item.text.trim()
    if (addition.isEmpty()) {
        return ChatComposerApplyResult(currentText, images, attachments)
    }
    if (shouldAttachLargeText(addition)) {
        return ChatComposerApplyResult(currentText, images, attachments, largeTextToStage = addition)
    }
    val separator = if (currentText.text.isBlank()) "" else "\n\n"
    val atEnd = currentText.copy(selection = TextRange(currentText.text.length))
    return ChatComposerApplyResult(
        text = insertTextAtCursor(atEnd, separator + addition),
        images = images,
        attachments = attachments,
    )
}
