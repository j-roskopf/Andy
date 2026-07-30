package app.andy.domain

import app.andy.model.AccessibilityNode

internal fun offsetBoundsY(bounds: String?, deltaY: Int): String? {
    if (deltaY == 0) return bounds
    val parsed = parseBounds(bounds) ?: return null
    return boundsString(
        parsed[0],
        parsed[1] - deltaY,
        parsed[2],
        parsed[3] - deltaY,
    )
}

internal fun boundsString(left: Int, top: Int, right: Int, bottom: Int): String = "[$left,$top][$right,$bottom]"

/**
 * Resolves hierarchy bounds into display/screen space for the mirror overlay.
 *
 * Uiautomator usually returns screen coordinates, but nodes inside Compose [ScrollView]
 * containers can stay in content space until [AccessibilityNode.scrollOffsetY] (from
 * `dumpsys activity top` `mScrollY`) is applied. Never guesses scroll from mirror gestures.
 */
internal fun resolveHighlightBounds(
    bounds: String?,
    root: AccessibilityNode?,
    node: AccessibilityNode?,
): String? {
    if (bounds == null || root == null || node == null) return bounds
    return screenBoundsForOverlay(bounds, root, node)
}

internal fun screenBoundsForOverlay(
    bounds: String,
    root: AccessibilityNode,
    node: AccessibilityNode,
): String? {
    val parsed = parseBounds(bounds) ?: return bounds
    val scrollable = node.nearestScrollableAncestor(root) ?: return bounds
    val viewport = parseBounds(scrollable.bounds) ?: return bounds
    val scrollY = scrollable.scrollOffsetY()
    val resolved = if (needsScrollTranslation(parsed, viewport, scrollY)) {
        offsetBoundsY(bounds, scrollY) ?: bounds
    } else {
        bounds
    }
    return clipToViewport(resolved, viewport, root)
}

/**
 * Decides whether [bounds] are still in scroll-content space and need `mScrollY` subtracted.
 * At scrollY=0 never translates (avoids drift when the viewport is already at the top).
 */
internal fun needsScrollTranslation(parsed: List<Int>, viewport: List<Int>, scrollY: Int): Boolean {
    if (scrollY == 0) return false
    val top = parsed[1]
    if (top >= viewport[1] + scrollY) return true
    if (top < viewport[1] && top + scrollY > viewport[1]) return true
    val screenTopIfContent = top - scrollY
    val inVisibleBand = top in viewport[1]..viewport[3]
    val contentWouldFit = screenTopIfContent in viewport[1]..viewport[3]
    return contentWouldFit && !inVisibleBand
}

internal fun AccessibilityNode.scrollOffsetY(): Int =
    attributes["scroll-y"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun clipToViewport(bounds: String, viewport: List<Int>, root: AccessibilityNode): String? {
    val parsed = parseBounds(bounds) ?: return bounds
    val displayWidth = parseBounds(root.bounds)?.let { it[2] - it[0] } ?: return bounds
    val displayHeight = parseBounds(root.bounds)?.let { it[3] - it[1] } ?: return bounds
    var left = parsed[0]
    var top = parsed[1]
    var right = parsed[2]
    var bottom = parsed[3]
    left = maxOf(left, viewport[0])
    top = maxOf(top, viewport[1])
    right = minOf(right, viewport[2])
    bottom = minOf(bottom, viewport[3])
    left = left.coerceIn(0, displayWidth)
    top = top.coerceIn(0, displayHeight)
    right = right.coerceIn(0, displayWidth)
    bottom = bottom.coerceIn(0, displayHeight)
    if (right <= left || bottom <= top) return null
    return boundsString(left, top, right, bottom)
}

internal fun AccessibilityNode.nearestScrollableAncestor(root: AccessibilityNode): AccessibilityNode? {
    val path = pathFromRoot(root) ?: return null
    return path.dropLast(1).lastOrNull { it.scrollable }
}

internal fun AccessibilityNode.pathFromRoot(root: AccessibilityNode): List<AccessibilityNode>? {
    fun walk(current: AccessibilityNode, path: List<AccessibilityNode>): List<AccessibilityNode>? {
        if (current.id == id) return path + current
        for (child in current.children) {
            walk(child, path + current)?.let { return it }
        }
        return null
    }
    return walk(root, emptyList())
}

internal fun boundsTop(bounds: String?): Int? = parseBounds(bounds)?.getOrNull(1)
