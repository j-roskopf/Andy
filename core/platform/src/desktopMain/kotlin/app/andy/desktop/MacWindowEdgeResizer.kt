package app.andy.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point

/**
 * In-content window edge hit targets for macOS.
 *
 * There is no public AppKit API to enlarge Tahoe's native resize hotspot (private
 * `NSThemeFrame` hit-testing). This is an app-level stand-in: thin Compose strips where
 * users aim inside the frame, driving AWT [java.awt.Window.setBounds] directly.
 *
 * Mid-drag we only mutate the AWT window (one [setBounds] per move). [WindowState] is
 * synced on drag end so we do not recompose the whole UI every pointer event.
 *
 * Place as the last child in the Window content. Skips top / top-left for traffic lights.
 */
@Composable
fun WindowScope.MacWindowEdgeResizer(
    state: WindowState,
    thickness: Dp = MacWindowEdgeResizerDefaults.Thickness,
) {
    val frame = window as? Frame
    if (!isMacOs() || frame?.isResizable != true) return
    if (frame.isUndecorated) return

    val latestState by rememberUpdatedState(state)
    val awtWindow = window

    Box(Modifier.fillMaxSize()) {
        EdgeHandle(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(thickness),
            cursor = Cursor.W_RESIZE_CURSOR,
            sides = SideFlags.Left,
            window = awtWindow,
            state = { latestState },
        )
        EdgeHandle(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(thickness),
            cursor = Cursor.E_RESIZE_CURSOR,
            sides = SideFlags.Right,
            window = awtWindow,
            state = { latestState },
        )
        EdgeHandle(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(thickness),
            cursor = Cursor.S_RESIZE_CURSOR,
            sides = SideFlags.Bottom,
            window = awtWindow,
            state = { latestState },
        )
        EdgeHandle(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(thickness)
                .height(thickness),
            cursor = Cursor.NE_RESIZE_CURSOR,
            sides = SideFlags.Right or SideFlags.Top,
            window = awtWindow,
            state = { latestState },
        )
        EdgeHandle(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(thickness)
                .height(thickness),
            cursor = Cursor.SW_RESIZE_CURSOR,
            sides = SideFlags.Left or SideFlags.Bottom,
            window = awtWindow,
            state = { latestState },
        )
        EdgeHandle(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(thickness)
                .height(thickness),
            cursor = Cursor.SE_RESIZE_CURSOR,
            sides = SideFlags.Right or SideFlags.Bottom,
            window = awtWindow,
            state = { latestState },
        )
    }
}

internal object MacWindowEdgeResizerDefaults {
    val Thickness = 12.dp
}

@Composable
private fun EdgeHandle(
    modifier: Modifier,
    cursor: Int,
    sides: Int,
    window: java.awt.Window,
    state: () -> WindowState,
) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(sides, window) {
                var startPointer = Point()
                var startPos = Point()
                var startSize = Dimension()
                fun syncStateFromWindow() {
                    val windowState = state()
                    windowState.size = DpSize(window.width.dp, window.height.dp)
                    windowState.position = WindowPosition(window.x.dp, window.y.dp)
                }
                detectDragGestures(
                    onDragStart = {
                        startPointer = mouseLocationOnScreen() ?: Point()
                        startSize = Dimension(window.width, window.height)
                        startPos = Point(window.x, window.y)
                    },
                    onDragEnd = { syncStateFromWindow() },
                    onDragCancel = { syncStateFromWindow() },
                ) { change, _ ->
                    change.consume()
                    val pointer = mouseLocationOnScreen() ?: return@detectDragGestures
                    val (pos, size) = macWindowResizedBounds(
                        sides = sides,
                        initialWindowPos = startPos,
                        initialWindowSize = startSize,
                        initialPointer = startPointer,
                        pointer = pointer,
                        minimumSize = Dimension(
                            window.minimumSize.width.coerceAtLeast(320),
                            window.minimumSize.height.coerceAtLeast(240),
                        ),
                    )
                    // Single AWT mutation — avoid setLocation+setSize (two events) and avoid
                    // writing WindowState here (that recomposes the whole app every move).
                    if (window.x != pos.x || window.y != pos.y ||
                        window.width != size.width || window.height != size.height
                    ) {
                        window.setBounds(pos.x, pos.y, size.width, size.height)
                    }
                }
            },
    )
}

/**
 * Pure resize geometry used by the drag handler (and unit tests).
 * Screen / AWT coordinates: +x right, +y down.
 */
internal fun macWindowResizedBounds(
    sides: Int,
    initialWindowPos: Point,
    initialWindowSize: Dimension,
    initialPointer: Point,
    pointer: Point,
    minimumSize: Dimension,
): Pair<Point, Dimension> {
    val diffX = pointer.x - initialPointer.x
    val diffY = pointer.y - initialPointer.y
    var newX = initialWindowPos.x
    var newY = initialWindowPos.y
    var newWidth = initialWindowSize.width
    var newHeight = initialWindowSize.height

    if (sides.contains(SideFlags.Left)) {
        newWidth = (initialWindowSize.width - diffX).coerceAtLeast(minimumSize.width)
        newX = initialWindowPos.x + initialWindowSize.width - newWidth
    } else if (sides.contains(SideFlags.Right)) {
        newWidth = (initialWindowSize.width + diffX).coerceAtLeast(minimumSize.width)
    }
    if (sides.contains(SideFlags.Top)) {
        newHeight = (initialWindowSize.height - diffY).coerceAtLeast(minimumSize.height)
        newY = initialWindowPos.y + initialWindowSize.height - newHeight
    } else if (sides.contains(SideFlags.Bottom)) {
        newHeight = (initialWindowSize.height + diffY).coerceAtLeast(minimumSize.height)
    }
    return Point(newX, newY) to Dimension(newWidth, newHeight)
}

internal object SideFlags {
    const val Left = 0x0001
    const val Top = 0x0010
    const val Right = 0x0100
    const val Bottom = 0x1000
}

private fun Int.contains(flag: Int): Boolean = this and flag == flag

private fun mouseLocationOnScreen(): Point? = MouseInfo.getPointerInfo()?.location
