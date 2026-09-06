package com.joetr.andy.mobile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RemoteDisplayTest {
    private val viewport = IntSize(1080, 1920)
    private val crop = DisplayCrop(0, 0, 0, 3448, 2346)

    @Test
    fun atFitZoomTheImageIsLetterboxedInsideTheViewport() {
        val mapping = computeMapping(viewport, crop, zoom = 1f, pan = Offset.Zero)
        assertEquals(0f, mapping.left, 0.5f)
        assertTrue("image must not exceed the viewport", mapping.width <= viewport.width + 0.5f)
        assertTrue(mapping.height <= viewport.height + 0.5f)
    }

    /** Pinch-zoom used to let the image be dragged off-screen entirely. */
    @Test
    fun panIsClampedToTheImageEdges() {
        val zoom = 4f
        val clamped = clampPan(viewport, crop, zoom, Offset(100_000f, 100_000f))
        val mapping = computeMapping(viewport, crop, zoom, clamped)
        assertTrue("left edge must not move inside the viewport", mapping.left <= 0.5f)
        assertTrue(mapping.left + mapping.width >= viewport.width - 0.5f)
    }

    @Test
    fun panIsZeroedOnAnAxisWhereTheImageIsSmallerThanTheViewport() {
        // The wide crop never fills the tall viewport vertically, so it stays centred.
        val clamped = clampPan(viewport, crop, zoom = 1f, pan = Offset(500f, 500f))
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
    }

    @Test
    fun panForCornerIsTheInverseOfComputeMapping() {
        val zoom = 2.5f
        val pan = panForCorner(viewport, crop, zoom, left = -120f, top = -80f)
        val mapping = computeMapping(viewport, crop, zoom, pan)
        assertEquals(-120f, mapping.left, 0.001f)
        assertEquals(-80f, mapping.top, 0.001f)
    }

    @Test
    fun viewCoordinatesMapBackToRemotePixels() {
        val mapping = computeMapping(viewport, crop, zoom = 1f, pan = Offset.Zero)
        val centre = mapping.toRemote(
            mapping.left + mapping.width / 2f,
            mapping.top + mapping.height / 2f,
        )
        assertTrue(abs(centre!!.first - crop.width / 2) <= 1)
        assertTrue(abs(centre.second - crop.height / 2) <= 1)
    }

    @Test
    fun touchMapsToTheRemotePixelUnderTheViewPoint() {
        // A 4×2 crop fitted into an 8×4 viewport → each remote pixel is 2×2 view pixels.
        val tiny = DisplayCrop(null, 0, 0, 4, 2)
        val mapping = computeMapping(IntSize(8, 4), tiny, zoom = 1f, pan = Offset.Zero)
        // Just inside the top-left remote pixel's view rect should stay on (0,0),
        // not round up into the neighbour the way the old roundToInt path did.
        assertEquals(0 to 0, mapping.toRemote(mapping.left + 0.1f, mapping.top + 0.1f))
        assertEquals(0 to 0, mapping.toRemote(mapping.left + 1.9f, mapping.top + 1.9f))
        assertEquals(1 to 0, mapping.toRemote(mapping.left + 2.1f, mapping.top + 0.5f))
        assertEquals(3 to 1, mapping.toRemote(mapping.left + mapping.width - 0.1f, mapping.top + mapping.height - 0.1f))
    }

    @Test
    fun touchesOutsideTheImageDoNotMapButClampedTouchesDo() {
        val mapping = computeMapping(viewport, crop, zoom = 1f, pan = Offset.Zero)
        assertEquals(null, mapping.toRemote(mapping.left - 40f, mapping.top + 10f))
        assertEquals(crop.x, mapping.toRemoteClamped(mapping.left - 40f, mapping.top + 10f)!!.first)
    }

    @Test
    fun samplerScalesTheCropIntoTheViewportBuffer() {
        val srcW = 8
        val srcH = 4
        val src = IntArray(srcW * srcH) { 0xFF000000.toInt() or it }
        val fullCrop = DisplayCrop(null, 0, 0, srcW, srcH)
        val dstW = 16
        val dstH = 8
        val dst = IntArray(dstW * dstH)
        val mapping = computeMapping(IntSize(dstW, dstH), fullCrop, 1f, Offset.Zero)

        sampleFrame(src, srcW, srcH, mapping, dst, dstW, dstH)

        // 2x upscale: the top-left dst pixel is src(0,0) and dst(2,2) is src(1,1).
        assertEquals(src[0], dst[0])
        assertEquals(src[srcW + 1], dst[2 * dstW + 2])
        // Bottom-right corner still resolves inside the source, never past its end.
        assertEquals(src[src.size - 1], dst[dst.size - 1])
    }

    @Test
    fun samplerBlacksOutLetterboxBarsInsteadOfLeavingStalePixels() {
        val srcW = 8
        val srcH = 2
        val src = IntArray(srcW * srcH) { 0xFFFFFFFF.toInt() }
        val fullCrop = DisplayCrop(null, 0, 0, srcW, srcH)
        val dstW = 8
        val dstH = 8
        val dst = IntArray(dstW * dstH) { 0xFF00FF00.toInt() } // stale green
        val mapping = computeMapping(IntSize(dstW, dstH), fullCrop, 1f, Offset.Zero)

        sampleFrame(src, srcW, srcH, mapping, dst, dstW, dstH)

        assertEquals(0xFF000000.toInt(), dst[0]) // top bar
        assertEquals(0xFF000000.toInt(), dst[dst.size - 1]) // bottom bar
        assertEquals(0xFFFFFFFF.toInt(), dst[dstH / 2 * dstW + dstW / 2]) // image band
    }

    @Test
    fun samplerOnlyReadsInsideTheSelectedDisplay() {
        // Two 4x3 monitors side by side; selecting the right one must never read the left.
        val srcW = 8
        val srcH = 3
        val src = IntArray(srcW * srcH) { if (it % srcW < 4) LEFT else RIGHT }
        val rightCrop = displayCrop(srcW, srcH, displayIndex = 1)
        val dstW = 8
        val dstH = 4
        val dst = IntArray(dstW * dstH)
        val mapping = computeMapping(IntSize(dstW, dstH), rightCrop, 1f, Offset.Zero)

        sampleFrame(src, srcW, srcH, mapping, dst, dstW, dstH)

        assertTrue(dst.none { it == LEFT })
        assertTrue(dst.any { it == RIGHT })
    }

    private companion object {
        const val LEFT = 0xFF112233.toInt()
        const val RIGHT = 0xFF445566.toInt()
    }
}
