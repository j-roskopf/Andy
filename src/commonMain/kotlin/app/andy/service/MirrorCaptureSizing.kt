package app.andy.service

/**
 * Scales a physical display size to the scrcpy `max_size` edge. `maxSize <= 0` means native
 * (unlimited), matching scrcpy's `max_size=0` contract.
 */
fun scaledCaptureSize(sourceWidth: Int, sourceHeight: Int, maxSize: Int): Pair<Int, Int> {
    val longestSide = maxOf(sourceWidth, sourceHeight).coerceAtLeast(1)
    val scale = when {
        maxSize <= 0 -> 1.0
        longestSide > maxSize -> maxSize.toDouble() / longestSide
        else -> 1.0
    }
    fun evenAtLeast(value: Int, minimum: Int): Int = maxOf(minimum, value and -2)
    return evenAtLeast((sourceWidth * scale).toInt(), 2) to evenAtLeast((sourceHeight * scale).toInt(), 2)
}
