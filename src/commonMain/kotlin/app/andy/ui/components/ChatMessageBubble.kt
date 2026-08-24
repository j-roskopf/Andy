package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

internal enum class ChatBubbleSender {
    User,
    Assistant,
    System,
}

internal enum class ChatBubbleGroup {
    Single,
    First,
    Middle,
    Last,
}

/** Astryx ChatMessageBubble — 28dp chat radius, sender-aware fill. */
@Composable
internal fun ChatMessageBubble(
    modifier: Modifier = Modifier,
    sender: ChatBubbleSender = ChatBubbleSender.Assistant,
    group: ChatBubbleGroup = ChatBubbleGroup.Single,
    variant: ChatBubbleVariant = ChatBubbleVariant.Filled,
    alignEnd: Boolean = sender == ChatBubbleSender.User,
    testTag: String? = null,
    name: String? = null,
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
    val verticalPadding = when (group) {
        ChatBubbleGroup.First -> PaddingValues(
            start = AndySpace.Space3,
            end = AndySpace.Space3,
            top = AndySpace.Space2,
            bottom = AndySpace.Space1,
        )
        ChatBubbleGroup.Middle -> PaddingValues(
            start = AndySpace.Space3,
            end = AndySpace.Space3,
            top = AndySpace.Space1,
            bottom = AndySpace.Space1,
        )
        ChatBubbleGroup.Last -> PaddingValues(
            start = AndySpace.Space3,
            end = AndySpace.Space3,
            top = AndySpace.Space1,
            bottom = AndySpace.Space2,
        )
        ChatBubbleGroup.Single -> PaddingValues(
            horizontal = AndySpace.Space3,
            vertical = AndySpace.Space2,
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
        }
    }
}

internal enum class ChatBubbleVariant {
    Filled,
    Ghost,
}

/** How far middle/last grouped bubbles draw into the previous item. */
internal val ChatBubbleGroupPullUp = 10.dp

@Composable
internal fun ChatBubbleText(
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
