package app.andy.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-native scrollbar for a [LazyListState].
 *
 * Desktop uses Compose Foundation [androidx.compose.foundation.VerticalScrollbar];
 * other targets are no-ops (browser / system scroll affordances).
 */
@Composable
internal expect fun PlatformLazyListScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = false,
)
