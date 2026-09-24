package app.andy.desktop

import java.awt.Dimension
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class MacWindowEdgeResizerTest {
    @Test
    fun rightEdgeGrowsWidth() {
        val (pos, size) = macWindowResizedBounds(
            sides = SideFlags.Right,
            initialWindowPos = Point(100, 50),
            initialWindowSize = Dimension(400, 300),
            initialPointer = Point(500, 200),
            pointer = Point(540, 200),
            minimumSize = Dimension(200, 150),
        )
        assertEquals(100, pos.x)
        assertEquals(50, pos.y)
        assertEquals(440, size.width)
        assertEquals(300, size.height)
    }

    @Test
    fun leftEdgeMovesOriginAndShrinksWidth() {
        val (pos, size) = macWindowResizedBounds(
            sides = SideFlags.Left,
            initialWindowPos = Point(100, 50),
            initialWindowSize = Dimension(400, 300),
            initialPointer = Point(100, 200),
            pointer = Point(140, 200),
            minimumSize = Dimension(200, 150),
        )
        assertEquals(140, pos.x)
        assertEquals(50, pos.y)
        assertEquals(360, size.width)
        assertEquals(300, size.height)
    }

    @Test
    fun bottomRightCornerRespectsMinimumSize() {
        val (pos, size) = macWindowResizedBounds(
            sides = SideFlags.Right or SideFlags.Bottom,
            initialWindowPos = Point(100, 50),
            initialWindowSize = Dimension(400, 300),
            initialPointer = Point(500, 350),
            pointer = Point(100, 100),
            minimumSize = Dimension(200, 150),
        )
        assertEquals(100, pos.x)
        assertEquals(50, pos.y)
        assertEquals(200, size.width)
        assertEquals(150, size.height)
    }

    @Test
    fun topRightMovesYWhenGrowingUpward() {
        val (pos, size) = macWindowResizedBounds(
            sides = SideFlags.Right or SideFlags.Top,
            initialWindowPos = Point(100, 50),
            initialWindowSize = Dimension(400, 300),
            initialPointer = Point(500, 50),
            pointer = Point(520, 20),
            minimumSize = Dimension(200, 150),
        )
        assertEquals(100, pos.x)
        assertEquals(20, pos.y)
        assertEquals(420, size.width)
        assertEquals(330, size.height)
    }
}
