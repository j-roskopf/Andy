package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Floating in-chat find bar. Caller owns query/match index and keyboard chords that open it.
 */
@Composable
fun ChatFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onFindNext: () -> Unit,
    onFindPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Bump to re-request focus (e.g. Cmd+F while the bar is already open). */
    focusNonce: Int = 0,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusNonce) {
        runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AndyColors.SurfaceRaised, RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, AndyColors.BorderEmphasized, RoundedCornerShape(AndyRadius.Control))
            .padding(horizontal = AndySpace.Space2, vertical = AndySpace.Space2)
            .testTag("chat-find-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
    ) {
        LucideIcon(Lucide.Search, TextSecondary, Modifier.size(14.dp))
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 120.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> {
                            onClose()
                            true
                        }
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            if (event.isShiftPressed) onFindPrevious() else onFindNext()
                            true
                        }
                        event.key == Key.DirectionDown -> {
                            onFindNext()
                            true
                        }
                        event.key == Key.DirectionUp -> {
                            onFindPrevious()
                            true
                        }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = DisplayFont,
                fontSize = 13.sp,
                color = TextPrimary,
            ),
            placeholder = {
                Text("Find in chat", color = TextSecondary, fontFamily = DisplayFont, fontSize = 13.sp)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onFindNext() }),
        )
        Text(
            text = when {
                query.isBlank() -> ""
                matchCount == 0 -> "No results"
                else -> "${(matchIndex + 1).coerceAtLeast(1)} of $matchCount"
            },
            color = if (query.isNotBlank() && matchCount == 0) AndyColors.Warning else TextSecondary,
            fontFamily = MonoFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        IconButton(
            onClick = onFindPrevious,
            enabled = matchCount > 0,
            contentDescription = "Previous match",
            modifier = Modifier.size(28.dp),
        ) {
            LucideIcon(Lucide.ChevronUp, if (matchCount > 0) TextPrimary else TextSecondary, Modifier.size(14.dp))
        }
        IconButton(
            onClick = onFindNext,
            enabled = matchCount > 0,
            contentDescription = "Next match",
            modifier = Modifier.size(28.dp),
        ) {
            LucideIcon(Lucide.ChevronDown, if (matchCount > 0) TextPrimary else TextSecondary, Modifier.size(14.dp))
        }
        IconButton(
            onClick = onClose,
            contentDescription = "Close find",
            modifier = Modifier.size(28.dp),
        ) {
            LucideIcon(Lucide.X, TextSecondary, Modifier.size(14.dp))
        }
    }
}
