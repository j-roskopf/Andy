package app.andy.ui.live

import app.andy.service.DhuCaptureFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DhuFrameLayoutTest {
    @Test
    fun fitRectLetterboxesWidePane() {
        val fit = DhuFrameLayout.fitRect(srcWidth = 800, srcHeight = 480, dstWidth = 1000, dstHeight = 480)
        assertEquals(800, fit.width)
        assertEquals(480, fit.height)
        assertEquals(100, fit.x)
        assertEquals(0, fit.y)
    }

    @Test
    fun fitRectLetterboxesTallPane() {
        val fit = DhuFrameLayout.fitRect(srcWidth = 800, srcHeight = 480, dstWidth = 400, dstHeight = 800)
        assertEquals(400, fit.width)
        assertEquals(240, fit.height)
        assertEquals(0, fit.x)
        assertEquals(280, fit.y)
    }

    @Test
    fun cropWindowChromeRemovesTitleBarStrip() {
        val width = 800
        val height = 508
        val chrome = 28
        val argb = IntArray(width * height) { i ->
            val y = i / width
            if (y < chrome) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
        }
        val cropped = DhuFrameLayout.cropWindowChrome(DhuCaptureFrame(width, height, argb, 1))
        assertEquals(800, cropped.width)
        assertEquals(480, cropped.height)
        assertTrue(cropped.argb.all { it == 0xFF00FF00.toInt() })
    }

    @Test
    fun cropWindowChromeNoOpsWhenAspectAlreadyMatches() {
        val frame = DhuCaptureFrame(800, 480, IntArray(800 * 480) { 1 }, 2)
        val cropped = DhuFrameLayout.cropWindowChrome(frame)
        assertEquals(480, cropped.height)
        assertEquals(frame.frameNumber, cropped.frameNumber)
    }

    @Test
    fun normalizePointerMapsThroughLetterbox() {
        val fit = DhuFitRect(x = 100, y = 0, width = 800, height = 480)
        val (nx, ny) = DhuFrameLayout.normalizePointer(100f, 0f, fit)
        assertEquals(0f, nx)
        assertEquals(0f, ny)
        val (cx, cy) = DhuFrameLayout.normalizePointer(500f, 240f, fit)
        assertEquals(0.5f, cx)
        assertEquals(0.5f, cy)
    }
}
