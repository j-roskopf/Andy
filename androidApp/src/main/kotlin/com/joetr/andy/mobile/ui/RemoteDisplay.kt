package com.joetr.andy.mobile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Crop of the remote framebuffer — null [displayIndex] means the full desktop. */
data class DisplayCrop(
    val displayIndex: Int?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** Where [crop] lands inside the viewport, in view pixels. */
data class ViewMapping(
    val crop: DisplayCrop,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun toRemote(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (width <= 1f || height <= 1f) return null
        if (viewX < left || viewY < top || viewX > left + width || viewY > top + height) return null
        // Floor (not round): map onto the remote pixel that actually paints under this
        // view point. Rounding biased clicks half a pixel toward higher coords and made
        // small controls feel like they needed an overshoot.
        val rx = crop.x + floor(((viewX - left) / width) * crop.width).toInt()
        val ry = crop.y + floor(((viewY - top) / height) * crop.height).toInt()
        return rx.coerceIn(crop.x, crop.x + crop.width - 1) to
            ry.coerceIn(crop.y, crop.y + crop.height - 1)
    }

    /** Nearest in-bounds remote point, so a drag that slips off the edge still tracks. */
    fun toRemoteClamped(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (width <= 1f || height <= 1f) return null
        val rx = crop.x + floor(((viewX - left) / width) * crop.width).toInt()
        val ry = crop.y + floor(((viewY - top) / height) * crop.height).toInt()
        return rx.coerceIn(crop.x, crop.x + crop.width - 1) to
            ry.coerceIn(crop.y, crop.y + crop.height - 1)
    }
}

/** Infer equal-width monitor count from an ultrawide spanning framebuffer. */
internal fun inferDisplayCount(desktopWidth: Int, desktopHeight: Int): Int {
    if (desktopWidth <= 0 || desktopHeight <= 0) return 1
    val ratio = desktopWidth.toFloat() / desktopHeight.toFloat()
    return when {
        ratio >= 3.4f -> 3
        ratio >= 2.05f -> 2
        else -> 1
    }
}

internal fun displayCrop(
    desktopWidth: Int,
    desktopHeight: Int,
    displayIndex: Int?,
): DisplayCrop {
    val w = desktopWidth.coerceAtLeast(1)
    val h = desktopHeight.coerceAtLeast(1)
    if (displayIndex == null) return DisplayCrop(null, 0, 0, w, h)
    val count = inferDisplayCount(w, h).coerceAtLeast(1)
    val index = displayIndex.coerceIn(0, count - 1)
    val tile = w / count
    val x = index * tile
    val width = if (index == count - 1) w - x else tile
    return DisplayCrop(index, x, 0, width.coerceAtLeast(1), h)
}

/**
 * Cover scale — fills the viewport on both axes (zoom = 1 baseline).
 * Widescreen remotes on a portrait phone overflow horizontally and stay finger-sized.
 */
internal fun fillScale(viewport: IntSize, crop: DisplayCrop): Float {
    if (viewport.width <= 0 || viewport.height <= 0) return 0f
    return max(
        viewport.width.toFloat() / crop.width.toFloat(),
        viewport.height.toFloat() / crop.height.toFloat(),
    )
}

internal fun drawnSize(viewport: IntSize, crop: DisplayCrop, zoom: Float): Pair<Float, Float> {
    val fill = fillScale(viewport, crop)
    if (fill <= 0f) return 0f to 0f
    return crop.width * fill * zoom to crop.height * fill * zoom
}

/** True when the drawn image exceeds the viewport on either axis (panning can move it). */
internal fun canPan(viewport: IntSize, crop: DisplayCrop, zoom: Float): Boolean {
    val (drawW, drawH) = drawnSize(viewport, crop, zoom)
    return drawW > viewport.width + 0.5f || drawH > viewport.height + 0.5f
}

internal fun computeMapping(
    viewport: IntSize,
    crop: DisplayCrop,
    zoom: Float,
    pan: Offset,
): ViewMapping {
    val (drawW, drawH) = drawnSize(viewport, crop, zoom)
    if (drawW <= 0f || drawH <= 0f) return ViewMapping(crop, 0f, 0f, 0f, 0f)
    val left = (viewport.width - drawW) / 2f + pan.x
    val top = (viewport.height - drawH) / 2f + pan.y
    return ViewMapping(crop, left, top, drawW, drawH)
}

/** The pan that puts the drawn image's top-left corner at ([left], [top]). */
internal fun panForCorner(
    viewport: IntSize,
    crop: DisplayCrop,
    zoom: Float,
    left: Float,
    top: Float,
): Offset {
    val (drawW, drawH) = drawnSize(viewport, crop, zoom)
    if (drawW <= 0f || drawH <= 0f) return Offset.Zero
    return Offset(
        left - (viewport.width - drawW) / 2f,
        top - (viewport.height - drawH) / 2f,
    )
}

/**
 * Keep the image anchored to the viewport: it can never be dragged past its own edges,
 * and it stays centred on any axis where it is smaller than the viewport.
 */
internal fun clampPan(viewport: IntSize, crop: DisplayCrop, zoom: Float, pan: Offset): Offset {
    val (drawW, drawH) = drawnSize(viewport, crop, zoom)
    if (drawW <= 0f || drawH <= 0f) return Offset.Zero
    val maxX = max((drawW - viewport.width) / 2f, 0f)
    val maxY = max((drawH - viewport.height) / 2f, 0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/**
 * Pan that places remote pixel ([remoteX], [remoteY]) under view point ([viewX], [viewY]).
 * Used to keep the same remote content on-screen when the viewport resizes (keyboard, chrome).
 */
internal fun panShowingRemoteAt(
    viewport: IntSize,
    crop: DisplayCrop,
    zoom: Float,
    remoteX: Int,
    remoteY: Int,
    viewX: Float,
    viewY: Float,
): Offset {
    val (drawW, drawH) = drawnSize(viewport, crop, zoom)
    if (drawW <= 0f || drawH <= 0f || crop.width <= 0 || crop.height <= 0) return Offset.Zero
    val left = viewX - ((remoteX - crop.x + 0.5f) / crop.width.toFloat()) * drawW
    val top = viewY - ((remoteY - crop.y + 0.5f) / crop.height.toFloat()) * drawH
    return clampPan(viewport, crop, zoom, panForCorner(viewport, crop, zoom, left, top))
}

/**
 * Zoom multiplier that keeps crop pixels the same size on screen after a viewport resize.
 * Cover [fillScale] changes with keyboard/chrome height; compensate so the view does not
 * appear to zoom in/out.
 */
internal fun zoomPreservingAbsoluteScale(
    oldViewport: IntSize,
    newViewport: IntSize,
    crop: DisplayCrop,
    oldZoom: Float,
    minZoom: Float = 1f,
    maxZoom: Float = Float.MAX_VALUE,
): Float {
    val oldFill = fillScale(oldViewport, crop)
    val newFill = fillScale(newViewport, crop)
    if (oldFill <= 0f || newFill <= 0f) return oldZoom.coerceIn(minZoom, maxZoom)
    return (oldZoom * oldFill / newFill).coerceIn(minZoom, maxZoom)
}

private const val FIXED_BITS = 16
private const val FIXED_ONE = 65536f
private const val OPAQUE_BLACK = 0xFF000000.toInt()

/**
 * Nearest-neighbour resample of the visible part of [src] into a viewport-sized buffer.
 *
 * The remote desktop can be 6896x2346 (64 MB). Handing that whole bitmap to
 * `drawImage` re-uploads all 64 MB to the GPU on every frame, because any pixel
 * write bumps the bitmap's generation id. Sampling down to viewport resolution
 * first caps the per-frame upload at a few MB and keeps zoomed-in views crisp,
 * since we sample the source at the zoom level actually being displayed.
 */
internal fun sampleFrame(
    src: IntArray,
    srcWidth: Int,
    srcHeight: Int,
    mapping: ViewMapping,
    dst: IntArray,
    dstWidth: Int,
    dstHeight: Int,
) {
    if (dstWidth <= 0 || dstHeight <= 0) return
    val crop = mapping.crop
    val x0 = max(0, ceil(mapping.left).toInt())
    val y0 = max(0, ceil(mapping.top).toInt())
    val x1 = min(dstWidth, floor(mapping.left + mapping.width).toInt())
    val y1 = min(dstHeight, floor(mapping.top + mapping.height).toInt())

    val covered = x0 <= 0 && y0 <= 0 && x1 >= dstWidth && y1 >= dstHeight
    if (!covered) java.util.Arrays.fill(dst, 0, dstWidth * dstHeight, OPAQUE_BLACK)
    if (mapping.width < 1f || mapping.height < 1f) return
    if (x1 <= x0 || y1 <= y0) return

    val maxSx = min(crop.x + crop.width, srcWidth) - 1
    val maxSy = min(crop.y + crop.height, srcHeight) - 1
    if (maxSx < crop.x || maxSy < crop.y) return

    val scaleX = crop.width / mapping.width
    val scaleY = crop.height / mapping.height
    val stepX = (scaleX * FIXED_ONE).toInt().coerceAtLeast(1)
    val stepY = (scaleY * FIXED_ONE).toInt().coerceAtLeast(1)
    val startX = ((crop.x + (x0 - mapping.left) * scaleX) * FIXED_ONE).toInt()
    var fy = ((crop.y + (y0 - mapping.top) * scaleY) * FIXED_ONE).toInt()

    for (py in y0 until y1) {
        val sy = (fy shr FIXED_BITS).coerceIn(crop.y, maxSy)
        val rowBase = sy * srcWidth
        var fx = startX
        var di = py * dstWidth + x0
        for (px in x0 until x1) {
            val sx = (fx shr FIXED_BITS).coerceIn(crop.x, maxSx)
            dst[di++] = src[rowBase + sx]
            fx += stepX
        }
        fy += stepY
    }
}
