package app.andy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.markdown_copy
import app.andy.rememberCopyText
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

enum class ChatBubbleSender {
    User,
    Assistant,
    System,
}

enum class ChatBubbleGroup {
    Single,
    First,
    Middle,
    Last,
}

/**
 * Astryx ChatMessageBubble — 28dp chat radius, sender-aware fill.
 *
 * Optional [metadata] (Astryx ChatMessageMetadata) is always shown under the bubble when set.
 */
@Composable
fun ChatMessageBubble(
    modifier: Modifier = Modifier,
    sender: ChatBubbleSender = ChatBubbleSender.Assistant,
    group: ChatBubbleGroup = ChatBubbleGroup.Single,
    variant: ChatBubbleVariant = ChatBubbleVariant.Filled,
    alignEnd: Boolean = sender == ChatBubbleSender.User,
    testTag: String? = null,
    name: String? = null,
    metadata: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = andyTokens()
    val background = when {
        variant == ChatBubbleVariant.Ghost -> Color.Transparent
        sender == ChatBubbleSender.User -> tokens.accent.copy(alpha = if (AndyColors.isLight) 0.12f else 0.20f)
        sender == ChatBubbleSender.System -> tokens.neutralFill
        else -> AndyColors.SurfaceRaised
    }
    val shape = chatBubbleShape(group, alignEnd)
    val widthModifier = when {
        alignEnd -> Modifier.widthIn(max = 640.dp)
        variant == ChatBubbleVariant.Ghost -> Modifier.fillMaxWidth()
        else -> Modifier.fillMaxWidth(0.85f)
    }
    val groupPullUp = when (group) {
        ChatBubbleGroup.Middle, ChatBubbleGroup.Last -> -ChatBubbleGroupPullUp
        else -> 0.dp
    }
    // Chat radius is 28dp — keep content clear of the curve. Metadata sits outside the
    // bubble, so bottom padding stays full even when a copy/timestamp row follows.
    val horizontalPad = AndySpace.Space4
    val verticalPadding = when (group) {
        ChatBubbleGroup.First -> PaddingValues(
            start = horizontalPad,
            end = horizontalPad,
            top = AndySpace.Space3,
            bottom = AndySpace.Space2,
        )
        ChatBubbleGroup.Middle -> PaddingValues(
            start = horizontalPad,
            end = horizontalPad,
            top = AndySpace.Space2,
            bottom = AndySpace.Space2,
        )
        ChatBubbleGroup.Last -> PaddingValues(
            start = horizontalPad,
            end = horizontalPad,
            top = AndySpace.Space2,
            bottom = AndySpace.Space3,
        )
        ChatBubbleGroup.Single -> PaddingValues(
            horizontal = horizontalPad,
            vertical = AndySpace.Space3,
        )
    }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Column(widthModifier.offset(y = groupPullUp)) {
            if (name != null) {
                Text(
                    name,
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(
                        start = AndySpace.Space3,
                        bottom = AndySpace.Space1,
                    ),
                )
            }
            Column(
                Modifier
                    .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
                    .clip(shape)
                    .background(background, shape)
                    .padding(verticalPadding),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                content = content,
            )
            if (metadata != null) {
                // Start a touch right of the text inset so time/copy trail the first glyph.
                // Avoid fillMaxWidth — it used to stretch user bubbles and park the icon far away.
                Box(
                    Modifier
                        .align(Alignment.Start)
                        .padding(
                            start = horizontalPad + AndySpace.Space1,
                            end = horizontalPad,
                            top = if (alignEnd) 2.dp else 0.dp,
                            bottom = AndySpace.Space1,
                        ),
                ) {
                    metadata()
                }
            }
        }
    }
}

enum class ChatBubbleVariant {
    Filled,
    Ghost,
}

/** How far middle/last grouped bubbles draw into the previous item. */
val ChatBubbleGroupPullUp = 10.dp

@Composable
fun ChatBubbleText(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Text(
        text,
        modifier = modifier,
        color = TextPrimary,
        fontFamily = DisplayFont,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
    )
}

/**
 * Astryx ChatMessageMetadata — `timestamp · footer · status`.
 */
@Composable
fun ChatMessageMetadata(
    modifier: Modifier = Modifier,
    timestamp: String? = null,
    status: String? = null,
    reverse: Boolean = false,
    footer: @Composable (() -> Unit)? = null,
) {
    val hasContent = timestamp != null || footer != null || status != null
    if (!hasContent) return
    Row(
        modifier,
        horizontalArrangement = if (reverse) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AndySpace.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (timestamp != null) {
                MetaLabel(timestamp)
                if (footer != null || status != null) MetaDot()
            }
            footer?.invoke()
            if (footer != null && status != null) MetaDot()
            if (status != null) MetaLabel(status)
        }
    }
}

private const val CopiedFeedbackMillis = 1_400L

/** Icon-only copy control for [ChatMessageMetadata] footer. */
@Composable
fun ChatMessageCopyAction(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val copyText = rememberCopyText()
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (!justCopied) return@LaunchedEffect
        delay(CopiedFeedbackMillis)
        justCopied = false
    }
    // Material's default 48dp touch target leaves a large empty gap under short bubbles.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Tooltip(text = "Copied", forceVisible = justCopied, delayMillis = 0) {
            Box(
                modifier
                    .size(16.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (justCopied) "Copied" else "Copy message"
                    }
                    .clickable(onClickLabel = "Copy message", enabled = !justCopied) {
                        copyText(text)
                        justCopied = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (justCopied) {
                    Text(
                        "✓",
                        color = Green,
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Image(
                        painter = painterResource(Res.drawable.markdown_copy),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        colorFilter = ColorFilter.tint(TextSecondary.copy(alpha = 0.72f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontFamily = DisplayFont,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun MetaDot() {
    Text("·", color = TextSecondary.copy(alpha = 0.55f), fontSize = 12.sp)
}

private fun chatBubbleShape(group: ChatBubbleGroup, alignEnd: Boolean): RoundedCornerShape {
    val chat = AndyRadius.Chat
    val compact = AndyRadius.Menu
    val innerStart = if (alignEnd) chat else compact
    val innerEnd = if (alignEnd) compact else chat
    return when (group) {
        ChatBubbleGroup.Single -> RoundedCornerShape(chat)
        ChatBubbleGroup.First -> RoundedCornerShape(
            topStart = chat,
            topEnd = chat,
            bottomEnd = innerEnd,
            bottomStart = innerStart,
        )
        ChatBubbleGroup.Middle -> RoundedCornerShape(
            topStart = innerStart,
            topEnd = innerEnd,
            bottomEnd = innerEnd,
            bottomStart = innerStart,
        )
        ChatBubbleGroup.Last -> RoundedCornerShape(
            topStart = innerStart,
            topEnd = innerEnd,
            bottomEnd = chat,
            bottomStart = chat,
        )
    }
}
