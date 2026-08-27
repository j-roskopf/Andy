package app.andy.domain

import app.andy.model.ClipFormat
import app.andy.model.RecordingExportRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingExportTest {
    @Test
    fun trimFrameIndicesIsInclusiveAtBothBoundaries() {
        val timestamps = listOf(0L, 100L, 200L, 300L, 400L)
        assertEquals(listOf(1, 2, 3), trimFrameIndices(timestamps, startMillis = 100L, endMillis = 300L))
        assertEquals(listOf(0), trimFrameIndices(timestamps, startMillis = 0L, endMillis = 0L))
        assertEquals(emptyList(), trimFrameIndices(timestamps, startMillis = 300L, endMillis = 100L))
        assertEquals(emptyList(), trimFrameIndices(emptyList(), startMillis = 0L, endMillis = 100L))
    }

    @Test
    fun trimmedTimestampsMapsIndicesBackToValues() {
        val timestamps = listOf(0L, 100L, 200L, 300L)
        assertEquals(listOf(100L, 200L), trimmedTimestamps(timestamps, startMillis = 100L, endMillis = 200L))
    }

    @Test
    fun trimFrameIndicesUniformSpreadsFramesAcrossSourceWindow() {
        // 5 frames evenly spread across a 400ms window: 0, 100, 200, 300, 400.
        val indices = trimFrameIndicesUniform(
            frameCount = 5,
            sourceStartMillis = 0L,
            sourceEndMillis = 400L,
            startMillis = 100L,
            endMillis = 300L,
        )
        assertEquals(listOf(1, 2, 3), indices)
    }

    @Test
    fun trimFrameIndicesUniformHandlesSingleFrame() {
        assertEquals(listOf(0), trimFrameIndicesUniform(1, 500L, 500L, startMillis = 0L, endMillis = 1000L))
        assertEquals(emptyList(), trimFrameIndicesUniform(1, 500L, 500L, startMillis = 600L, endMillis = 1000L))
        assertEquals(emptyList(), trimFrameIndicesUniform(0, 0L, 1000L, startMillis = 0L, endMillis = 1000L))
    }

    @Test
    fun uniformFrameTimestampsSpansStartToEnd() {
        assertEquals(listOf(0L, 250L, 500L, 750L, 1000L), uniformFrameTimestamps(5, startMillis = 0L, endMillis = 1000L))
        assertEquals(listOf(500L), uniformFrameTimestamps(1, startMillis = 500L, endMillis = 999L))
        assertEquals(emptyList(), uniformFrameTimestamps(0, startMillis = 0L, endMillis = 1000L))
    }

    @Test
    fun sampleFrameIndicesAtRateDedupesAndIncludesFinalFrame() {
        // A 30fps source over 1 second sampled down to 10fps should not repeat frames and
        // should still land on the last in-range frame.
        val timestamps = (0..30).map { it * (1000L / 30) }
        val sampled = sampleFrameIndicesAtRate(timestamps, startMillis = 0L, endMillis = 1000L, fps = 10)

        assertEquals(sampled.distinct().size, sampled.size, "must not repeat the same frame back-to-back")
        assertTrue(sampled.isNotEmpty())
        assertEquals(timestamps.indices.last, sampled.last())
    }

    @Test
    fun sampleFrameIndicesAtRateHandlesInvalidInput() {
        assertEquals(emptyList(), sampleFrameIndicesAtRate(emptyList(), 0L, 100L, fps = 10))
        assertEquals(emptyList(), sampleFrameIndicesAtRate(listOf(0L, 100L), 0L, 100L, fps = 0))
        assertEquals(emptyList(), sampleFrameIndicesAtRate(listOf(0L, 100L), 200L, 100L, fps = 10))
    }

    @Test
    fun validateRecordingExportRequestAcceptsWellFormedRequest() {
        val request = RecordingExportRequest(
            id = "recording-1",
            startMillis = 1000L,
            endMillis = 2000L,
            format = ClipFormat.Gif,
        )
        assertEquals(emptyList(), validateRecordingExportRequest(request, availableStartMillis = 0L, availableEndMillis = 3000L))
    }

    @Test
    fun validateRecordingExportRequestFlagsBlankId() {
        val request = RecordingExportRequest(id = "", startMillis = 0L, endMillis = 1000L, format = ClipFormat.Mp4)
        assertTrue(validateRecordingExportRequest(request, 0L, 1000L).any { it.contains("id") })
    }

    @Test
    fun validateRecordingExportRequestFlagsInvertedRange() {
        val request = RecordingExportRequest(id = "r", startMillis = 2000L, endMillis = 1000L, format = ClipFormat.Mp4)
        assertTrue(validateRecordingExportRequest(request, 0L, 3000L).any { it.contains("End time") })
    }

    @Test
    fun validateRecordingExportRequestFlagsRangeOutsideAvailableWindow() {
        val request = RecordingExportRequest(id = "r", startMillis = -100L, endMillis = 500L, format = ClipFormat.Mp4)
        assertTrue(validateRecordingExportRequest(request, availableStartMillis = 0L, availableEndMillis = 1000L).isNotEmpty())

        val overEnd = RecordingExportRequest(id = "r", startMillis = 0L, endMillis = 5000L, format = ClipFormat.Mp4)
        assertTrue(validateRecordingExportRequest(overEnd, availableStartMillis = 0L, availableEndMillis = 1000L).isNotEmpty())
    }

    @Test
    fun validateRecordingExportRequestFlagsNonPositiveScaleAndFps() {
        val badScale = RecordingExportRequest(id = "r", startMillis = 0L, endMillis = 1000L, format = ClipFormat.Mp4, scale = 0)
        assertTrue(validateRecordingExportRequest(badScale, 0L, 1000L).any { it.contains("Scale") })

        val badFps = RecordingExportRequest(id = "r", startMillis = 0L, endMillis = 1000L, format = ClipFormat.Mp4, fps = 0)
        assertTrue(validateRecordingExportRequest(badFps, 0L, 1000L).any { it.contains("Frame rate") })
    }

    @Test
    fun validateRecordingExportRequestCapsNonMp4FpsAtThirty() {
        val request = RecordingExportRequest(id = "r", startMillis = 0L, endMillis = 1000L, format = ClipFormat.Gif, fps = 60)
        assertTrue(validateRecordingExportRequest(request, 0L, 1000L).any { it.contains("30 fps") })

        // MP4 has no such cap.
        val mp4Request = request.copy(format = ClipFormat.Mp4)
        assertTrue(validateRecordingExportRequest(mp4Request, 0L, 1000L).none { it.contains("30 fps") })
    }

    @Test
    fun estimateExportedClipBytesIsZeroForDegenerateInput() {
        val request = RecordingExportRequest(id = "r", startMillis = 1000L, endMillis = 1000L, format = ClipFormat.Gif)
        assertEquals(0L, estimateExportedClipBytes(request, sourceWidthPx = 1920, sourceHeightPx = 1080))
        assertEquals(
            0L,
            estimateExportedClipBytes(
                request.copy(endMillis = 2000L),
                sourceWidthPx = 0,
                sourceHeightPx = 0,
            ),
        )
    }

    @Test
    fun estimateExportedClipBytesScalesWithDurationAndFormat() {
        val base = RecordingExportRequest(id = "r", startMillis = 0L, endMillis = 2000L, format = ClipFormat.Gif, scale = 480, fps = 10)
        val gifBytes = estimateExportedClipBytes(base, sourceWidthPx = 1920, sourceHeightPx = 1080)
        val webpBytes = estimateExportedClipBytes(base.copy(format = ClipFormat.WebP), sourceWidthPx = 1920, sourceHeightPx = 1080)
        val pngBytes = estimateExportedClipBytes(base.copy(format = ClipFormat.PngSequence), sourceWidthPx = 1920, sourceHeightPx = 1080)
        val mp4Bytes = estimateExportedClipBytes(base.copy(format = ClipFormat.Mp4), sourceWidthPx = 1920, sourceHeightPx = 1080)

        assertTrue(gifBytes > 0L)
        assertTrue(webpBytes > 0L && webpBytes < gifBytes, "WebP should be denser than GIF for equivalent content")
        assertTrue(pngBytes > gifBytes, "an uncompressed-ish PNG sequence should be larger than a quantized GIF")
        assertTrue(mp4Bytes > 0L)

        val longer = estimateExportedClipBytes(base.copy(endMillis = 4000L), sourceWidthPx = 1920, sourceHeightPx = 1080)
        assertTrue(longer > gifBytes, "doubling duration should roughly double estimated size")
    }
}
