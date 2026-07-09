package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.andy.horizontalResizeCursor
import app.andy.ui.theme.PaneDividerTint
import app.andy.verticalResizeCursor

@Composable
internal fun PaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val density = LocalDensity.current.density
    Box(
        Modifier.width(14.dp)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .background(PaneDividerTint.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.x / density) }
            },
    ) {
        Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(PaneDividerTint))
    }
}

@Composable
internal fun HorizontalPaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val density = LocalDensity.current.density
    Box(
        Modifier.fillMaxWidth()
            .height(18.dp)
            .verticalResizeCursor()
            .background(PaneDividerTint.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.y / density) }
            },
    ) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).background(PaneDividerTint, RoundedCornerShape(2.dp)))
    }
}
