package app.andy.domain

import app.andy.model.AccessibilityNode
import kotlin.math.roundToInt

internal fun parseBounds(bounds: String?): List<Int>? {
    if (bounds.isNullOrBlank()) return null
    val values = Regex("""\d+""").findAll(bounds).map { it.value.toInt() }.toList()
    return values.takeIf { it.size == 4 }
}

internal fun AccessibilityNode.findBestNodeAt(x: Int, y: Int): AccessibilityNode? {
    val candidates = proximityCandidatesAt(x, y)
    val interactiveCandidates = candidates.filter { it.isActionable }
    val selectableCandidates = interactiveCandidates.ifEmpty {
        candidates.filter { it.depth > 0 && !it.isFullScreenContainer }
    }.ifEmpty { candidates }
    return selectableCandidates
        .sortedWith(
            compareBy<AccessibilityHitCandidate> { it.selectionScore }
                .thenByDescending { it.labelScore }
                .thenByDescending { it.depth }
                .thenByDescending { it.drawingOrder },
        )
        .firstOrNull()
        ?.node
}

internal data class AccessibilityHitCandidate(
    val node: AccessibilityNode,
    val depth: Int,
    val area: Int,
    val drawingOrder: Int,
    val distanceSquared: Int,
    val labelScore: Int,
    val isFullScreenContainer: Boolean,
) {
    val isActionable: Boolean get() = node.clickable || node.focusable || !node.contentDescription.isNullOrBlank() ||
        !node.text.isNullOrBlank() || !node.resourceId.isNullOrBlank()
    val selectionScore: Int get() = distanceSquared * 16 +
        area / 35 -
        labelScore * 12_000 -
        depth * 450 +
        if (isFullScreenContainer) 1_000_000 else 0
}

internal fun AccessibilityNode.proximityCandidatesAt(x: Int, y: Int, depth: Int = 0): List<AccessibilityHitCandidate> {
    val childHits = children.flatMap { it.proximityCandidatesAt(x, y, depth + 1) }
    val bounds = parseBounds(bounds) ?: return childHits
    val distanceSquared = distanceSquaredToBounds(x, y, bounds)
    if (distanceSquared > 180 * 180) return childHits
    val area = ((bounds[2] - bounds[0]).coerceAtLeast(1)) * ((bounds[3] - bounds[1]).coerceAtLeast(1))
    return childHits + AccessibilityHitCandidate(
        node = this,
        depth = depth,
        area = area,
        drawingOrder = attributes["drawing-order"]?.toIntOrNull() ?: 0,
        distanceSquared = distanceSquared,
        labelScore = listOf(text, contentDescription, hint, resourceId).count { !it.isNullOrBlank() } +
            (if (!contentDescription.isNullOrBlank()) 3 else 0) +
            (if (clickable) 3 else 0) +
            (if (focusable) 1 else 0),
        isFullScreenContainer = depth <= 2 && area > 1_200_000 && text.isNullOrBlank() &&
            contentDescription.isNullOrBlank() && resourceId.isNullOrBlank(),
    )
}

/**
 * Display pixel size for hierarchy bounds. Prefer the captured root node's bounds — uiautomator
 * coordinates share that space — then `wm size`, then the cached device listing.
 */
internal fun resolveHierarchyDisplaySize(
    rootBounds: String?,
    wmWidth: Int,
    wmHeight: Int,
    deviceScreenSize: String? = null,
): Pair<Int, Int>? {
    parseBounds(rootBounds)?.let { bounds ->
        val width = bounds[2] - bounds[0]
        val height = bounds[3] - bounds[1]
        if (width > 1 && height > 1) return width to height
    }
    if (wmWidth > 1 && wmHeight > 1) return wmWidth to wmHeight
    deviceScreenSize
        ?.split('x', '×', limit = 2)
        ?.let { parts ->
            val width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
            val height = parts.getOrNull(1)?.trim()?.substringBefore(' ')?.toIntOrNull() ?: 0
            if (width > 1 && height > 1) return width to height
        }
    return null
}

/** Normalized 0–1 content UV for mirror overlays (left, top, right, bottom). */
internal fun highlightContentUv(
    bounds: String?,
    displayWidth: Int?,
    displayHeight: Int?,
    streamWidth: Int?,
    streamHeight: Int?,
): FloatArray? {
    val parsed = parseBounds(bounds) ?: return null
    if (displayWidth != null && displayHeight != null && displayWidth > 0 && displayHeight > 0) {
        return floatArrayOf(
            parsed[0].toFloat() / displayWidth,
            parsed[1].toFloat() / displayHeight,
            parsed[2].toFloat() / displayWidth,
            parsed[3].toFloat() / displayHeight,
        )
    }
    val width = streamWidth ?: return null
    val height = streamHeight ?: return null
    if (width <= 0 || height <= 0) return null
    return floatArrayOf(
        parsed[0].toFloat() / width,
        parsed[1].toFloat() / height,
        parsed[2].toFloat() / width,
        parsed[3].toFloat() / height,
    )
}

/** Maps hierarchy bounds (display/`wm size` pixels) into scrcpy stream pixels for mirror overlays. */
internal fun scaleBoundsToStreamSpace(
    bounds: String?,
    displayWidth: Int,
    displayHeight: Int,
    streamWidth: Int,
    streamHeight: Int,
): String? {
    val parsed = parseBounds(bounds) ?: return null
    if (displayWidth <= 0 || displayHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) return bounds
    if (displayWidth == streamWidth && displayHeight == streamHeight) return bounds
    val scaleX = streamWidth.toFloat() / displayWidth
    val scaleY = streamHeight.toFloat() / displayHeight
    val left = (parsed[0] * scaleX).toInt()
    val top = (parsed[1] * scaleY).toInt()
    val right = (parsed[2] * scaleX).toInt()
    val bottom = (parsed[3] * scaleY).toInt()
    return "[$left,$top][$right,$bottom]"
}

/** Maps a mirror click (stream pixels) into display pixels for hierarchy hit-testing. */
internal fun scalePointFromStreamToDisplay(
    x: Int,
    y: Int,
    displayWidth: Int,
    displayHeight: Int,
    streamWidth: Int,
    streamHeight: Int,
): Pair<Int, Int> {
    if (displayWidth <= 0 || displayHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) return x to y
    val scaleX = displayWidth.toFloat() / streamWidth
    val scaleY = displayHeight.toFloat() / streamHeight
    return (x * scaleX).toInt() to (y * scaleY).toInt()
}

internal fun effectiveHighlightBounds(
    bounds: String?,
    displayWidth: Int?,
    displayHeight: Int?,
    streamWidth: Int?,
    streamHeight: Int?,
): List<Int>? {
    val uv = highlightContentUv(bounds, displayWidth, displayHeight, streamWidth, streamHeight) ?: return null
    val width = streamWidth ?: return null
    val height = streamHeight ?: return null
    if (width <= 0 || height <= 0) return null
    return listOf(
        (uv[0] * width).roundToInt(),
        (uv[1] * height).roundToInt(),
        (uv[2] * width).roundToInt(),
        (uv[3] * height).roundToInt(),
    )
}

internal fun distanceSquaredToBounds(x: Int, y: Int, bounds: List<Int>): Int {
    val dx = when {
        x < bounds[0] -> bounds[0] - x
        x > bounds[2] -> x - bounds[2]
        else -> 0
    }
    val dy = when {
        y < bounds[1] -> bounds[1] - y
        y > bounds[3] -> y - bounds[3]
        else -> 0
    }
    return dx * dx + dy * dy
}
