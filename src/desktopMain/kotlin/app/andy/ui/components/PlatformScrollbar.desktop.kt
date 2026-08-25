package app.andy.ui.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.TextSecondary

@Composable
internal actual fun PlatformLazyListScrollbar(
    listState: LazyListState,
    modifier: Modifier,
    reverseLayout: Boolean,
) {
    // Platform Foundation scrollbar (macOS/Windows/Linux native-feeling track).
    val style = ScrollbarStyle(
        minimalHeight = 36.dp,
        thickness = 9.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 250,
        unhoverColor = AndyColors.TextTertiary.copy(alpha = 0.35f),
        hoverColor = TextSecondary.copy(alpha = 0.65f),
    )
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState = listState),
        modifier = modifier,
        reverseLayout = reverseLayout,
        style = style,
    )
}
