package app.andy.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlyingChatMessageTest {
    private val density = Density(density = 1f, fontScale = 1f)

    @Test
    fun queueTargetSitsJustAboveComposerEndAligned() {
        val composer = Rect(left = 40f, top = 400f, right = 340f, bottom = 520f)
        val target = flyingChatMessageTargetFromRects(
            composerBox = composer,
            transcriptBox = Rect(0f, 0f, 400f, 380f),
            queued = true,
            density = density,
        )
        assertEquals(composer.right, target.right)
        assertTrue(target.bottom <= composer.top)
        assertTrue(target.width <= composer.width)
        assertTrue(target.height > 0f)
    }

    @Test
    fun transcriptTargetSitsAtBottomTrailingEdge() {
        val composer = Rect(left = 40f, top = 400f, right = 340f, bottom = 520f)
        val transcript = Rect(left = 0f, top = 0f, right = 400f, bottom = 380f)
        val target = flyingChatMessageTargetFromRects(
            composerBox = composer,
            transcriptBox = transcript,
            queued = false,
            density = density,
        )
        assertTrue(target.right <= transcript.right)
        assertTrue(target.bottom <= transcript.bottom)
        assertTrue(target.left >= transcript.left)
        assertTrue(target.width <= 640f)
    }

    @Test
    fun flightExitsRightThenEntersFromRight() {
        val start = Rect(left = 40f, top = 400f, right = 340f, bottom = 520f)
        val end = Rect(left = 200f, top = 260f, right = 380f, bottom = 360f)
        val containerRight = 400f

        val atStart = flyingChatMessageFrame(start, end, containerRight, progress = 0f)
        assertEquals(start.left, atStart.left)
        assertEquals(start.top, atStart.top)

        val midExit = flyingChatMessageFrame(start, end, containerRight, progress = 0.5f)
        assertTrue(midExit.left > start.left)
        assertTrue(midExit.left < containerRight)
        assertEquals(start.top, midExit.top)

        val fullyOff = flyingChatMessageFrame(start, end, containerRight, progress = 1f)
        assertEquals(containerRight, fullyOff.left)
        assertEquals(start.top, fullyOff.top)

        val midEnter = flyingChatMessageFrame(start, end, containerRight, progress = 1.5f)
        assertTrue(midEnter.left < containerRight)
        assertTrue(midEnter.left > end.left)
        assertEquals(end.top, midEnter.top)

        val landed = flyingChatMessageFrame(start, end, containerRight, progress = 2f)
        assertEquals(end.left, landed.left)
        assertEquals(end.top, landed.top)
        assertEquals(1f, landed.alpha)
    }
}
