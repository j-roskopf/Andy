package app.andy.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.OrchestrationPreferences
import app.andy.model.orchestrationSkillProviderHint
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextSecondary

/**
 * Keyboard handling for `/` and `@` autocomplete popovers that stay non-focusable
 * so the composer [androidx.compose.foundation.text.BasicTextField] keeps focus.
 * Returns true when the key is consumed.
 */
internal fun handleComposerAutocompleteKey(
    event: KeyEvent,
    slashOpen: Boolean,
    slashCount: Int,
    mentionOpen: Boolean,
    mentionCount: Int,
    highlight: Int,
    onHighlightChange: (Int) -> Unit,
    onSelectSlash: (Int) -> Unit,
    onSelectMention: (Int) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    // Escape must work even with zero matches (debounce / no results); navigation
    // still requires a nonempty list so Enter/Tab do not select an invalid index.
    if ((slashOpen || mentionOpen) && event.key == Key.Escape) {
        onDismiss()
        return true
    }

    val navigatingSlash = slashOpen && slashCount > 0
    val navigatingMention = !navigatingSlash && mentionOpen && mentionCount > 0
    if (!navigatingSlash && !navigatingMention) return false

    val count = if (navigatingSlash) slashCount else mentionCount
    val select: (Int) -> Unit = if (navigatingSlash) onSelectSlash else onSelectMention
    val clamped = highlight.coerceIn(0, count - 1)

    return when (event.key) {
        Key.DirectionDown -> {
            onHighlightChange((clamped + 1) % count)
            true
        }
        Key.DirectionUp -> {
            onHighlightChange((clamped - 1 + count) % count)
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (event.isShiftPressed) return false
            select(clamped)
            true
        }
        Key.Tab -> {
            select(clamped)
            true
        }
        else -> false
    }
}

/**
 * Autocomplete row that uses one highlight for both keyboard selection and mouse hover —
 * Material3's default menu-item hover layer (`onSurface` @ 8%), not [app.andy.ui.theme.AndyColors.SurfaceHover].
 */
@Composable
internal fun ComposerAutocompleteMenuItem(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .background(if (selected || hovered) highlight else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(MenuDefaults.DropdownMenuItemContentPadding),
        content = content,
    )
}

/**
 * Slash-menu row for a command or skill. Provider hints for andy orchestration skills
 * sit directly under the name so they stay visible even when Cursor (or another ACP
 * host) advertises the skill as a green provider command with a long description.
 */
@Composable
internal fun ComposerSlashEntryMenuContent(
    token: String,
    tokenColor: Color,
    description: String,
    orchestrationPrefs: OrchestrationPreferences,
) {
    val providerHint = remember(token, orchestrationPrefs) {
        orchestrationSkillProviderHint(token, orchestrationPrefs)
    }
    val menuDescription = remember(token, description, providerHint) {
        if (providerHint == null) description else orchestrationSlashMenuDescription(description)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(token, color = tokenColor, fontFamily = MonoFont, fontSize = 12.sp)
        providerHint?.let { hint ->
            Text(
                hint,
                color = Cyan,
                fontFamily = MonoFont,
                fontSize = 10.sp,
                maxLines = 2,
            )
        }
        menuDescription.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text,
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = if (providerHint != null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** First sentence only — drops the long "Use when…" trigger prose from SKILL.md descriptions. */
internal fun orchestrationSlashMenuDescription(description: String): String {
    val trimmed = description.trim()
    if (trimmed.isEmpty()) return trimmed
    val sentenceEnd = trimmed.indexOf(". ").takeIf { it > 0 }
        ?: trimmed.indexOf(".").takeIf { it > 0 && it < trimmed.lastIndex }
    return sentenceEnd?.let { trimmed.substring(0, it + 1).trim() } ?: trimmed
}
