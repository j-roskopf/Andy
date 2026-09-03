package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.andyTokens

/** Astryx ChatSendButton — primary icon-only md button, accent when sendable. */
@Composable
fun ChatSendButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isSending: Boolean = false,
    isStopShown: Boolean = false,
    onStop: (() -> Unit)? = null,
) {
    val tokens = andyTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = if (pressed) 0.98f else 1f
    val active = isStopShown || (enabled && !isSending)
    val background = when {
        isStopShown -> tokens.neutralFill
        !enabled || isSending -> AndyColors.SurfaceHover
        else -> tokens.accent
    }
    val iconColor = when {
        isStopShown -> TextPrimary
        !enabled || isSending -> AndyColors.TextDisabled
        else -> tokens.onAccent
    }
    Box(
        modifier
            .size(AndyLayout.ControlHeightMd)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = active,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (isStopShown) onStop?.invoke() else onClick()
                },
            )
            .semantics { contentDescription = if (isStopShown) "Stop" else "Send" },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isSending -> Spinner(spinnerSize = SpinnerSize.Sm, shade = SpinnerShade.Subtle)
            isStopShown -> LucideIcon(Lucide.Square, iconColor, Modifier.size(AndyLayout.IconMd))
            else -> LucideIcon(Lucide.ArrowUp, iconColor, Modifier.size(AndyLayout.IconMd))
        }
    }
}

