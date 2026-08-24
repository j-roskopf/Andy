package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

/** Astryx avatar size tiers (xsm 20 · sm 24 · md 36 · lg 48 · xl 128). */
internal enum class AvatarSize {
    Xsm,
    Sm,
    Md,
    Lg,
    Xl,
}

internal fun AvatarSize.toDp(): Dp = when (this) {
    AvatarSize.Xsm -> 20.dp
    AvatarSize.Sm -> 24.dp
    AvatarSize.Md -> 36.dp
    AvatarSize.Lg -> 48.dp
    AvatarSize.Xl -> 128.dp
}

/**
 * Astryx Avatar — circular image or initials fallback.
 * Pass [content] for custom media (e.g. agent provider icons); otherwise [name] drives initials.
 */
@Composable
internal fun Avatar(
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.Md,
    name: String? = null,
    content: @Composable BoxScope.() -> Unit = {
        AvatarInitials(name = name, size = size)
    },
) {
    val diameter = size.toDp()
    val tokens = andyTokens()
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(tokens.neutralFill, CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun AvatarInitials(
    name: String?,
    size: AvatarSize,
) {
    val label = name
        ?.trim()
        ?.firstOrNull()
        ?.uppercaseChar()
        ?.toString()
        ?: "?"
    val diameter = size.toDp()
    Text(
        label,
        color = TextSecondary,
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = (diameter.value * InitialsFontSizeRatio).sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Initials scale — Astryx uses 40% of avatar diameter. */
private const val InitialsFontSizeRatio = 0.4f
