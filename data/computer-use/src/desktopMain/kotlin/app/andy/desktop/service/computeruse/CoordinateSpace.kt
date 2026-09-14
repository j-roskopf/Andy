package app.andy.desktop.service.computeruse

/**
 * Virtual desktop coordinate space in logical points, origin top-left of primary display.
 * Converts to/from per-display physical pixels using backing scale + origin.
 */
data class DisplayGeometry(
    val id: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
    val primary: Boolean,
)

object CoordinateSpace {
    data class Point(val x: Int, val y: Int)

    /** Logical tool API point → physical pixel point for CGEvent / capture. */
    fun toPhysical(logical: Point, displays: List<DisplayGeometry>): Point {
        val display = displays.firstOrNull { contains(it, logical.x, logical.y) }
            ?: displays.firstOrNull { it.primary }
            ?: displays.firstOrNull()
            ?: return logical
        val localX = logical.x - display.x
        val localY = logical.y - display.y
        return Point(
            x = display.x + (localX * display.scale).toInt(),
            y = display.y + (localY * display.scale).toInt(),
        )
    }

    fun contains(display: DisplayGeometry, x: Int, y: Int): Boolean =
        x >= display.x && x < display.x + display.width &&
            y >= display.y && y < display.y + display.height

    fun unionBounds(displays: List<DisplayGeometry>): HostRect? {
        if (displays.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (d in displays) {
            minX = minOf(minX, d.x)
            minY = minOf(minY, d.y)
            maxX = maxOf(maxX, d.x + d.width)
            maxY = maxOf(maxY, d.y + d.height)
        }
        return HostRect(minX, minY, maxX - minX, maxY - minY)
    }
}

data class HostRect(val x: Int, val y: Int, val width: Int, val height: Int)
