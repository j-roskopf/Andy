package app.andy.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Keeps [content] in composition after the first visit so local UI state
 * (selection, scroll, drafts) survives navigating away and back.
 * When inactive the pane collapses to zero size so it does not intercept input.
 */
@Composable
internal fun RetainedDestination(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    var visited by remember { mutableStateOf(false) }
    SideEffect {
        if (active) visited = true
    }
    if (!visited) return
    Box(if (active) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
        content()
    }
}
