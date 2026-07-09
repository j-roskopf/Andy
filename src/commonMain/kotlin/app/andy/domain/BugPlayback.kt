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
    val action = actions
        .filter { parseBugActionPoint(it) != null }
        .minByOrNull { kotlin.math.abs(it.timestampMillis - playbackMillis) }
        ?.takeIf { kotlin.math.abs(it.timestampMillis - playbackMillis) <= BugPointerHighlightMillis }
        ?: return null
    val point = parseBugActionPoint(action) ?: return null
    val age = kotlin.math.abs(playbackMillis - action.timestampMillis)
    return BugPointerEvent(
        x = point.first,
        y = point.second,
        progress = (age.toFloat() / BugPointerHighlightMillis).coerceIn(0f, 1f),
    )
}

internal fun parseBugActionPoint(action: BugAction): Pair<Int, Int>? {
    if (action.kind != "input") return null
    val text = listOfNotNull(action.label, action.detail).joinToString(" ")
    val match = Regex("""(\d+),(\d+)""").find(text) ?: return null
    val x = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
    val y = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
    return x to y
}
