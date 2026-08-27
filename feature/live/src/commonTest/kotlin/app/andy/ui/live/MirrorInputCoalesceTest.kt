package app.andy.ui.live

import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import kotlin.test.Test
import kotlin.test.assertEquals

class MirrorInputCoalesceTest {
    @Test
    fun collapsesConsecutiveTouchMoves() {
        val queued = ArrayDeque(
            listOf(
                MirrorInput.Touch(MirrorTouchAction.Move, 10, 10),
                MirrorInput.Touch(MirrorTouchAction.Move, 20, 20),
                MirrorInput.Touch(MirrorTouchAction.Move, 30, 30),
                MirrorInput.Touch(MirrorTouchAction.Up, 40, 40),
            ),
        )
        val result = coalesceMirrorInputs(MirrorInput.Touch(MirrorTouchAction.Down, 1, 1)) {
            queued.removeFirstOrNull()
        }
        assertEquals(
            listOf(
                MirrorInput.Touch(MirrorTouchAction.Down, 1, 1),
                MirrorInput.Touch(MirrorTouchAction.Move, 30, 30),
                MirrorInput.Touch(MirrorTouchAction.Up, 40, 40),
            ),
            result,
        )
    }

    @Test
    fun preservesNonMoveEventsInOrder() {
        val queued = ArrayDeque(
            listOf(
                MirrorInput.Key(4),
                MirrorInput.Touch(MirrorTouchAction.Move, 5, 5),
                MirrorInput.Home,
            ),
        )
        val result = coalesceMirrorInputs(MirrorInput.Back) { queued.removeFirstOrNull() }
        assertEquals(
            listOf(
                MirrorInput.Back,
                MirrorInput.Key(4),
                MirrorInput.Touch(MirrorTouchAction.Move, 5, 5),
                MirrorInput.Home,
            ),
            result,
        )
    }
}
