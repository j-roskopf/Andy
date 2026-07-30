package app.andy.domain

import app.andy.model.ClipFormat
import app.andy.model.RecordingExportRequest

/**
 * Frame indices from [timestampsMillis] falling within `[startMillis, endMillis]`, inclusive at
 * both ends. Pure so the off-by-one behavior at range boundaries (a frame timestamp exactly
 * equal to the trim handle) is table-testable without a real capture.
 */
internal fun trimFrameIndices(timestampsMillis: List<Long>, startMillis: Long, endMillis: Long): List<Int> {
    if (timestampsMillis.isEmpty() || endMillis < startMillis) return emptyList()
    return timestampsMillis.indices.filter { index -> timestampsMillis[index] in startMillis..endMillis }
}

/** Convenience wrapper over [trimFrameIndices] returning the timestamps themselves. */
internal fun trimmedTimestamps(timestampsMillis: List<Long>, startMillis: Long, endMillis: Long): List<Long> =
    trimFrameIndices(timestampsMillis, startMillis, endMillis).map { timestampsMillis[it] }

/**
 * Frame indices for a source with no per-frame timestamps (e.g. a legacy capture or a
 * uniform-rate re-encode) — spreads [frameCount] frames evenly across
 * `[sourceStartMillis, sourceEndMillis]` and keeps the ones inside the requested trim range.
 */
internal fun trimFrameIndicesUniform(
    frameCount: Int,
    sourceStartMillis: Long,
    sourceEndMillis: Long,
    startMillis: Long,
    endMillis: Long,
): List<Int> {
    if (frameCount <= 0 || endMillis < startMillis) return emptyList()
    if (frameCount == 1) {
        return if (sourceStartMillis in startMillis..endMillis) listOf(0) else emptyList()
    }
    val spanMillis = (sourceEndMillis - sourceStartMillis).coerceAtLeast(1L)
    return (0 until frameCount).filter { index ->
        val timestamp = sourceStartMillis + (spanMillis * index) / (frameCount - 1)
        timestamp in startMillis..endMillis
    }
}

/**
 * Builds synthetic, evenly-spaced frame timestamps for a capture with no per-frame timing
 * metadata (older captures / edge cases). Mirrors how a uniform-rate re-encode would land.
 */
internal fun uniformFrameTimestamps(frameCount: Int, startMillis: Long, endMillis: Long): List<Long> {
    if (frameCount <= 0) return emptyList()
    if (frameCount == 1) return listOf(startMillis)
    val spanMillis = (endMillis - startMillis).coerceAtLeast(0L)
    return (0 until frameCount).map { index -> startMillis + (spanMillis * index) / (frameCount - 1) }
}

/**
 * Nearest-frame indices sampled at [fps] across `[startMillis, endMillis]`, deduplicated so a
 * source slower than the requested output rate does not repeat the same frame back-to-back.
 * Used to cap GIF/WebP/PNG-sequence output size independent of the source capture frame rate.
 */
internal fun sampleFrameIndicesAtRate(
    timestampsMillis: List<Long>,
    startMillis: Long,
    endMillis: Long,
    fps: Int,
): List<Int> {
    if (timestampsMillis.isEmpty() || fps <= 0 || endMillis < startMillis) return emptyList()
    val stepMillis = (1000.0 / fps).coerceAtLeast(1.0)
    val indices = mutableListOf<Int>()
    var lastIndex = -1
    var t = startMillis.toDouble()
    while (t <= endMillis) {
        val index = nearestFrameIndex(timestampsMillis, t.toLong())
        if (index != lastIndex) {
            indices += index
            lastIndex = index
        }
        t += stepMillis
    }
    // Always include the final in-range frame so a trimmed clip doesn't end early.
    val lastInRange = trimFrameIndices(timestampsMillis, startMillis, endMillis).lastOrNull()
    if (lastInRange != null && indices.lastOrNull() != lastInRange) indices += lastInRange
    return indices
}

private fun nearestFrameIndex(timestampsMillis: List<Long>, targetMillis: Long): Int {
    var best = 0
    var bestDiff = Long.MAX_VALUE
    for (index in timestampsMillis.indices) {
        val diff = kotlin.math.abs(timestampsMillis[index] - targetMillis)
        if (diff < bestDiff) {
            bestDiff = diff
            best = index
        }
    }
    return best
}

/** Validation errors for an export request; empty when the request is well-formed. */
internal fun validateRecordingExportRequest(
    request: RecordingExportRequest,
    availableStartMillis: Long,
    availableEndMillis: Long,
): List<String> = buildList {
    if (request.id.isBlank()) add("A recording id is required")
    if (request.endMillis <= request.startMillis) add("End time must be after start time")
    if (request.startMillis < availableStartMillis || request.endMillis > availableEndMillis) {
        add("Trim range must be within the recorded window ($availableStartMillis..$availableEndMillis)")
    }
    if (request.scale <= 0) add("Scale must be a positive width in pixels")
    if (request.fps <= 0) add("Frame rate must be positive")
    if (request.format != ClipFormat.Mp4 && request.fps > 30) {
        add("GIF/WebP/PNG sequence export is capped at 30 fps")
    }
}

/**
 * Rough pre-export size estimate so an unbounded GIF/WebP is visible before it is written —
 * GitHub and most chat tools cap upload size. Coefficients are conservative approximations
 * (bytes per output pixel after quantization/compression), not a byte-exact prediction.
 */
internal fun estimateExportedClipBytes(
    request: RecordingExportRequest,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
): Long {
    val durationSeconds = (request.endMillis - request.startMillis).coerceAtLeast(0L) / 1000.0
    if (durationSeconds <= 0.0 || sourceWidthPx <= 0 || sourceHeightPx <= 0) return 0L
    val outputWidth = request.scale.coerceAtLeast(1)
    val outputHeight = (sourceHeightPx.toLong() * outputWidth / sourceWidthPx).coerceAtLeast(1L)
    val frameCount = (durationSeconds * request.fps).coerceAtLeast(1.0)
    val pixelsPerFrame = outputWidth.toLong() * outputHeight
    return when (request.format) {
        ClipFormat.Mp4 -> (durationSeconds * MP4_BYTES_PER_SECOND).toLong()
        ClipFormat.Gif -> (frameCount * pixelsPerFrame * GIF_BYTES_PER_PIXEL).toLong()
        ClipFormat.WebP -> (frameCount * pixelsPerFrame * WEBP_BYTES_PER_PIXEL).toLong()
        ClipFormat.PngSequence -> (frameCount * pixelsPerFrame * PNG_BYTES_PER_PIXEL).toLong()
    }.coerceAtLeast(0L)
}

/** ~700kbps H.264, a reasonable default bitrate for a short trimmed clip. */
private const val MP4_BYTES_PER_SECOND = 700_000.0 / 8.0

/** Palette-quantized GIF after palettegen/paletteuse (or an equivalent Kotlin-side quantizer). */
private const val GIF_BYTES_PER_PIXEL = 0.22

/** Animated WebP is typically 2-3x denser than GIF for the same content. */
private const val WEBP_BYTES_PER_PIXEL = 0.09

/** One PNG per frame; PNG compresses screen captures reasonably well. */
private const val PNG_BYTES_PER_PIXEL = 0.6
