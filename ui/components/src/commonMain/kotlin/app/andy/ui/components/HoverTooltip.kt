package app.andy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Back-compat alias — prefer [Tooltip].
 *
 * Hand-rolled rather than Material3's `TooltipBox`, which has no show-delay, or foundation's
 * `TooltipArea`, which has one but is desktop-only — this is common code shared with the web
 * target. The popup is non-focusable so it cannot steal focus from the composer's text field.
 */
@Composable
fun HoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Long = TooltipDelayMillis,
    content: @Composable () -> Unit,
) = Tooltip(text = text, modifier = modifier, delayMillis = delayMillis, content = content)

/** @see TooltipDelayMillis */
const val HoverTooltipDelayMillis = TooltipDelayMillis
