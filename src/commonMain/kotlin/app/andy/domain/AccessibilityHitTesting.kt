package app.andy.domain

import app.andy.model.AccessibilityNode

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
            if (!contentDescription.isNullOrBlank()) 3 else 0 +
            if (clickable) 3 else 0 +
            if (focusable) 1 else 0,
        isFullScreenContainer = depth <= 2 && area > 1_200_000 && text.isNullOrBlank() &&
            contentDescription.isNullOrBlank() && resourceId.isNullOrBlank(),
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
