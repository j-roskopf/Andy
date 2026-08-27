package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextSecondary
import app.andy.ui.theme.andyTokens

/**
 * Read-only PowerSearch-style field — bordered tokenizer shell that opens search on click.
 *
 * Visual markers: field chrome, search glyph, placeholder, optional shortcut hint.
 */
@Composable
fun PowerSearchField(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortcutHint: String? = null,
    contentDescription: String = placeholder,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tokens = andyTokens()
    val borderColor = when {
        focused -> tokens.accent
        hovered -> AndyColors.BorderEmphasized
        else -> AndyColors.BorderEmphasized
    }
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AndyLayout.FieldHeight)
            .clip(AndyShape.Interactive)
            .background(AndyColors.SurfaceRaised, AndyShape.Interactive)
            .border(1.dp, borderColor, AndyShape.Interactive)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        Text("⌕", color = TextSecondary.copy(alpha = 0.7f), fontSize = 14.sp)
        Text(
            placeholder,
            color = TextSecondary.copy(alpha = 0.66f),
            fontFamily = DisplayFont,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (shortcutHint != null) {
            Text(
                shortcutHint,
                color = TextSecondary.copy(alpha = 0.45f),
                fontFamily = MonoFont,
                fontSize = 10.sp,
            )
        }
    }
}
