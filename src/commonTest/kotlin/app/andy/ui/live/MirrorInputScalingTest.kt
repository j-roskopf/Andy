package app.andy.ui.live

import app.andy.service.MirrorInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MirrorInputScalingTest {
    private val portrait1080 = MirrorSourceSize(1080, 2400)
    private val portrait720 = MirrorSourceSize(720, 1600)

    @Test
    fun tapScalesProportionallyToTargetResolution() {
        // Center tap on a 1080x2400 source should land at the center on a 720x1600 target.
        val input = MirrorInput.Tap(x = 540, y = 1200)
        val scaled = scaleMirrorInput(input, portrait1080, portrait720)
        assertEquals(MirrorInput.Tap(x = 360, y = 800), scaled)
    }

    @Test
    fun touchScalesBothAxesIndependently() {
        val input = MirrorInput.Touch(app.andy.service.MirrorTouchAction.Down, x = 1080, y = 0)
        val scaled = scaleMirrorInput(input, portrait1080, portrait720)
        assertEquals(MirrorInput.Touch(app.andy.service.MirrorTouchAction.Down, x = 720, y = 0), scaled)
    }

    @Test
    fun swipeScalesStartAndEndPoints() {
        val input = MirrorInput.Swipe(startX = 0, startY = 0, endX = 1080, endY = 2400, durationMillis = 300)
        val scaled = scaleMirrorInput(input, portrait1080, portrait720) as MirrorInput.Swipe
        assertEquals(0, scaled.startX)
        assertEquals(0, scaled.startY)
        assertEquals(720, scaled.endX)
        assertEquals(1600, scaled.endY)
        assertEquals(300, scaled.durationMillis)
    }

    @Test
    fun identicalSourceAndTargetSizesReturnInputUnchanged() {
        val input = MirrorInput.Tap(x = 100, y = 200)
        val result = scaleMirrorInput(input, portrait1080, portrait1080)
        assertSame(input, result)
    }

    @Test
    fun invalidSourceSizePassesInputThrough() {
        val input = MirrorInput.Tap(x = 100, y = 200)
        val result = scaleMirrorInput(input, MirrorSourceSize(0, 0), portrait720)
        assertSame(input, result)
    }

    @Test
    fun nonCoordinateInputsPassThroughUnchanged() {
        assertSame(MirrorInput.Back, scaleMirrorInput(MirrorInput.Back, portrait1080, portrait720))
        assertSame(MirrorInput.Home, scaleMirrorInput(MirrorInput.Home, portrait1080, portrait720))
        assertSame(MirrorInput.Recents, scaleMirrorInput(MirrorInput.Recents, portrait1080, portrait720))
        assertSame(MirrorInput.Power, scaleMirrorInput(MirrorInput.Power, portrait1080, portrait720))
        val key = MirrorInput.Key(24)
        assertSame(key, scaleMirrorInput(key, portrait1080, portrait720))
        val text = MirrorInput.Text("hello")
        assertSame(text, scaleMirrorInput(text, portrait1080, portrait720))
    }

    @Test
    fun scalingUpEnlargesCoordinatesProportionally() {
        val input = MirrorInput.Tap(x = 360, y = 800)
        val scaled = scaleMirrorInput(input, portrait720, portrait1080)
        assertEquals(MirrorInput.Tap(x = 540, y = 1200), scaled)
    }
}
