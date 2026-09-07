package com.joetr.andy.mobile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RemoteDisplayTest {
    private val viewport = IntSize(1080, 1920)
    private val crop = DisplayCrop(0, 0, 0, 3448, 2346)

    @Test
    fun atFillZoomWideCropCoversViewportAndAllowsHorizontalPan() {
        val mapping = computeMapping(viewport, crop, zoom = 1f, pan = Offset.Zero)
        assertTrue("image must cover viewport width", mapping.width >= viewport.width - 0.5f)
        assertTrue("image must cover viewport height", mapping.height >= viewport.height - 0.5f)
        assertTrue(canPan(viewport, crop, zoom = 1f))
        val clamped = clampPan(viewport, crop, zoom = 1f, Offset(100_000f, 0f))
        assertTrue(abs(clamped.x) > 1f)
        assertEquals(0f, clamped.y, 0.001f)
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
    fun panIsZeroedOnAnAxisWhereTheImageDoesNotOverflow() {
        // Matched aspect: cover equals fit, no overflow, pan stays centred.
        val squareCrop = DisplayCrop(null, 0, 0, 100, 100)
        val squareViewport = IntSize(200, 200)
        val clamped = clampPan(squareViewport, squareCrop, zoom = 1f, Offset(500f, 500f))
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(0f, clamped.y, 0.001f)
        assertFalse(canPan(squareViewport, squareCrop, zoom = 1f))
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
        // A 4×2 crop covered into an 8×4 viewport → each remote pixel is 2×2 view pixels.
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
        val mapping = computeMapping(viewport, crop, zoom = 2f, pan = Offset.Zero)
        // At cover zoom the image already fills the viewport; pan left so left edge is past 0.
        val panned = computeMapping(
            viewport,
            crop,
            zoom = 2f,
            pan = clampPan(viewport, crop, 2f, Offset(10_000f, 0f)),
        )
        assertEquals(null, panned.toRemote(panned.left - 40f, panned.top + 10f))
        assertEquals(crop.x, panned.toRemoteClamped(panned.left - 40f, panned.top + 10f)!!.first)
        // Sanity: centre still maps.
        assertTrue(mapping.toRemote(viewport.width / 2f, viewport.height / 2f) != null)
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
    fun samplerBlacksOutUncoveredBarsInsteadOfLeavingStalePixels() {
        // Force a letterboxed mapping (narrower than cover) so uncovered rows stay black.
        val srcW = 8
        val srcH = 2
        val src = IntArray(srcW * srcH) { 0xFFFFFFFF.toInt() }
        val fullCrop = DisplayCrop(null, 0, 0, srcW, srcH)
        val dstW = 8
        val dstH = 8
        val dst = IntArray(dstW * dstH) { 0xFF00FF00.toInt() } // stale green
        val mapping = ViewMapping(fullCrop, left = 0f, top = 3f, width = 8f, height = 2f)

        sampleFrame(src, srcW, srcH, mapping, dst, dstW, dstH)

        assertEquals(0xFF000000.toInt(), dst[0]) // top bar
        assertEquals(0xFF000000.toInt(), dst[dst.size - 1]) // bottom bar
        assertEquals(0xFFFFFFFF.toInt(), dst[4 * dstW + 4]) // image band
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

    @Test
    fun viewportResizePreservesAbsoluteScale() {
        val oldViewport = IntSize(1080, 1920)
        val newViewport = IntSize(1080, 900)
        val oldZoom = 2f
        val newZoom = zoomPreservingAbsoluteScale(oldViewport, newViewport, crop, oldZoom, maxZoom = 20f)
        val oldFill = fillScale(oldViewport, crop)
        val newFill = fillScale(newViewport, crop)
        assertEquals(oldZoom * oldFill, newZoom * newFill, 0.001f)
    }

    @Test
    fun panShowingRemoteAtKeepsRemotePixelUnderViewPoint() {
        val zoom = 3f // both axes overflow so any in-crop pixel can sit at centre
        val remoteX = crop.x + crop.width / 3
        val remoteY = crop.y + crop.height / 4
        val viewX = viewport.width / 2f
        val viewY = viewport.height / 2f
        val pan = panShowingRemoteAt(viewport, crop, zoom, remoteX, remoteY, viewX, viewY)
        val mapped = computeMapping(viewport, crop, zoom, pan).toRemote(viewX, viewY)!!
        assertTrue(abs(mapped.first - remoteX) <= 1)
        assertTrue(abs(mapped.second - remoteY) <= 1)
    }

    @Test
    fun viewportResizeCanPreserveCentreRemotePixel() {
        val zoom = 1.5f
        val oldViewport = IntSize(1080, 1920)
        val newViewport = IntSize(1080, 900) // keyboard ate the bottom
        val oldPan = clampPan(oldViewport, crop, zoom, Offset(200f, -150f))
        val remote = computeMapping(oldViewport, crop, zoom, oldPan)
            .toRemoteClamped(oldViewport.width / 2f, oldViewport.height / 2f)!!
        val newPan = panShowingRemoteAt(
            newViewport,
            crop,
            zoom,
            remote.first,
            remote.second,
            newViewport.width / 2f,
            newViewport.height / 2f,
        )
        val mapped = computeMapping(newViewport, crop, zoom, newPan)
            .toRemote(newViewport.width / 2f, newViewport.height / 2f)!!
        assertTrue(abs(mapped.first - remote.first) <= 1)
        assertTrue(abs(mapped.second - remote.second) <= 1)
    }

    private companion object {
        const val LEFT = 0xFF112233.toInt()
        const val RIGHT = 0xFF445566.toInt()
    }
}
