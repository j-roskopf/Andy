package app.andy.domain

import app.andy.model.BugAction
import app.andy.model.BugReport

internal const val BugReplayFps = 15.0
internal const val BugActionHighlightWindowMillis = 1_200L
internal const val BugPointerHighlightMillis = 900L

internal data class BugPointerEvent(
    val x: Int,
    val y: Int,
    val progress: Float,
    val endX: Int? = null,
    val endY: Int? = null,
    val swipeProgress: Float = 1f,
)

internal fun bugPlaybackMillis(report: BugReport, frameIndex: Int, frameCount: Int): Long {
    val safeFrameCount = frameCount.coerceAtLeast(1)
    val clampedFrameIndex = frameIndex.coerceIn(0, safeFrameCount - 1)
    report.videoFrameTimestampsMillis.getOrNull(clampedFrameIndex)?.let { return it }
    val videoStart = report.videoStartedAtMillis
    val videoEnd = report.videoEndedAtMillis
    if (videoStart != null && videoEnd != null && videoEnd >= videoStart) {
        if (safeFrameCount == 1) return videoStart
        val progress = clampedFrameIndex.toDouble() / (safeFrameCount - 1).coerceAtLeast(1)
        return videoStart + ((videoEnd - videoStart) * progress).toLong()
    }
    val end = report.windowEndedAtMillis.takeIf { it > 0L } ?: report.capturedAtMillis
    val frameRate = report.videoFrameRate?.takeIf { it > 0.0 } ?: BugReplayFps
    val millisBeforeEnd = (((safeFrameCount - 1 - clampedFrameIndex) * 1000.0) / frameRate).toLong()
    return end - millisBeforeEnd
}

internal fun activeBugActionIndex(actions: List<BugAction>, playbackMillis: Long): Int {
    return actions
        .mapIndexed { index, action -> index to kotlin.math.abs(action.timestampMillis - playbackMillis) }
        .filter { (_, distance) -> distance <= BugActionHighlightWindowMillis }
        .minByOrNull { (_, distance) -> distance }
        ?.first
        ?: -1
}

internal fun activeBugPointerEvent(actions: List<BugAction>, playbackMillis: Long): BugPointerEvent? {
    return actions
        .mapNotNull { action -> action.toPointerCandidate(playbackMillis) }
        .minByOrNull(PointerCandidate::distanceMillis)
        ?.event
}

internal fun parseBugActionPoint(action: BugAction): Pair<Int, Int>? {
    if (action.kind != "input") return null
    val text = listOfNotNull(action.label, action.detail).joinToString(" ")
    val match = Regex("""(\d+),(\d+)""").find(text) ?: return null
    val x = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    val y = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
    return x to y
}

private data class PointerCandidate(
    val distanceMillis: Long,
    val event: BugPointerEvent,
)

private fun BugAction.toPointerCandidate(playbackMillis: Long): PointerCandidate? {
    val swipe = parseBugSwipe(this)
    if (swipe != null) {
        val startedAtMillis = timestampMillis - swipe.durationMillis
        val releasedAgoMillis = (playbackMillis - timestampMillis).coerceAtLeast(0L)
        val distance = when {
            playbackMillis < startedAtMillis -> startedAtMillis - playbackMillis
            playbackMillis > timestampMillis -> releasedAgoMillis
            else -> 0L
        }
        if (playbackMillis < startedAtMillis || releasedAgoMillis > BugPointerHighlightMillis) return null
        val gestureProgress = ((playbackMillis - startedAtMillis).toFloat() / swipe.durationMillis)
            .coerceIn(0f, 1f)
        return PointerCandidate(
            distanceMillis = distance,
            event = BugPointerEvent(
                x = swipe.startX,
                y = swipe.startY,
                endX = swipe.endX,
                endY = swipe.endY,
                swipeProgress = gestureProgress,
                progress = (releasedAgoMillis.toFloat() / BugPointerHighlightMillis).coerceIn(0f, 1f),
            ),
        )
    }

    val point = parseBugActionPoint(this) ?: return null
    val age = kotlin.math.abs(playbackMillis - timestampMillis)
    if (age > BugPointerHighlightMillis) return null
    return PointerCandidate(
        distanceMillis = age,
        event = BugPointerEvent(
            x = point.first,
            y = point.second,
            progress = (age.toFloat() / BugPointerHighlightMillis).coerceIn(0f, 1f),
        ),
    )
}

private data class BugSwipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMillis: Long,
)

private fun parseBugSwipe(action: BugAction): BugSwipe? {
    if (action.kind != "input" || !action.label.startsWith("Swipe", ignoreCase = true)) return null
    val detail = action.detail ?: return null
    val coordinates = SwipeCoordinatesRegex.find(detail) ?: return null
    val duration = SwipeDurationRegex.find(detail)?.groupValues?.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(1L) ?: return null
    return BugSwipe(
        startX = coordinates.groupValues[1].toIntOrNull() ?: return null,
        startY = coordinates.groupValues[2].toIntOrNull() ?: return null,
        endX = coordinates.groupValues[3].toIntOrNull() ?: return null,
        endY = coordinates.groupValues[4].toIntOrNull() ?: return null,
        durationMillis = duration,
    )
}

private val SwipeCoordinatesRegex = Regex("""(\d+),(\d+)\s*->\s*(\d+),(\d+)""")
private val SwipeDurationRegex = Regex("""(\d+)ms""")
