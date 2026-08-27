package app.andy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import app.andy.LocalSuppressHeavyweightSurfaces

/**
 * Keeps [content] in composition after the first visit so local UI state
 * (selection, scroll, drafts) survives navigating away and back.
 */
@Composable
fun RetainedDestination(
    active: Boolean,
    content: @Composable () -> Unit,
) {
    var visited by remember { mutableStateOf(false) }
    SideEffect {
        if (active) visited = true
    }
    if (!visited) return
    val parentSuppressHeavyweight = LocalSuppressHeavyweightSurfaces.current
    Box(if (active) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) {
        CompositionLocalProvider(
            LocalSuppressHeavyweightSurfaces provides (!active || parentSuppressHeavyweight),
        ) {
            content()
        }
    }
}
