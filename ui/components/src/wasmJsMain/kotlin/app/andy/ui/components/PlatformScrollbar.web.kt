package app.andy.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformLazyListScrollbar(
    listState: LazyListState,
    modifier: Modifier,
    reverseLayout: Boolean,
) {
    // Browser provides native scroll affordances; no custom overlay.
}
