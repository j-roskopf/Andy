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
}
