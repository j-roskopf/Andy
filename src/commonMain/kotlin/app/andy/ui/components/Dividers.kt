package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import app.andy.horizontalResizeCursor
import app.andy.ui.theme.AndyStroke
import app.andy.ui.theme.Border
import app.andy.ui.theme.PaneDividerTint
import app.andy.verticalResizeCursor

@Composable
internal fun AndyHorizontalDivider(
    modifier: Modifier = Modifier,
    color: Color = Border,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = AndyStroke.Hairline,
        color = color,
    )
}

@Composable
internal fun PaneDivider(
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
    /** When false, keeps the drag hit target but skips the hairline (e.g. rail already draws a border). */
    drawLine: Boolean = true,
) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val density = LocalDensity.current.density
    Box(
        Modifier
            .width(AndyStroke.PaneHandleHitWidth)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.x / density) }
            },
    ) {
        if (drawLine) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(AndyStroke.Hairline)
                    .fillMaxHeight()
                    .background(PaneDividerTint),
            )
        }
    }
}

@Composable
internal fun HorizontalPaneDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit = {}) {
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxWidth()
            .height(AndyStroke.PaneHandleHitHeight)
            .verticalResizeCursor()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { latestOnDragEnd() },
                    onDragCancel = { latestOnDragEnd() },
                ) { _, drag -> latestOnDrag(drag.y / density) }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(AndyStroke.Hairline)
                .background(PaneDividerTint),
        )
    }
}
