package app.andy.desktop.service

import app.andy.domain.sampleFrameIndicesAtRate
import app.andy.domain.trimFrameIndices
import app.andy.domain.uniformFrameTimestamps
import app.andy.domain.validateRecordingExportRequest
import app.andy.model.ClipFormat
import app.andy.model.ExportedClip
import app.andy.model.RecordingExportRequest
import app.andy.service.BugService
import app.andy.service.MirrorFrame
import app.andy.service.RecordingExportService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Exports a trimmed range of a saved recording (§E.4). Reuses [BugService.loadBugVideoFrame] /
 * [BugService.bugVideoFrameCount] for decoding — the same FFmpeg-backed frame store Bugs/
 * Recordings playback and scrubbing already depend on — instead of a second capture or decode
 * path through `capture.mp4`.
 */
class DesktopRecordingExportService(
    private val bugs: BugService,
) : RecordingExportService {

    override suspend fun export(request: RecordingExportRequest, localPath: String): Result<ExportedClip> =
        withContext(Dispatchers.IO) {
            runCatching { exportInternal(request, localPath) }
        }

    private suspend fun exportInternal(request: RecordingExportRequest, localPath: String): ExportedClip {
        val report = bugs.loadBug(request.id) ?: error("Recording not found: ${request.id}")
        val totalFrames = bugs.bugVideoFrameCount(request.id)
        require(totalFrames > 0) { "Recording has no video frames to export" }
        val availableStart = report.videoStartedAtMillis ?: report.windowStartedAtMillis
        val availableEnd = report.videoEndedAtMillis ?: report.windowEndedAtMillis
        val errors = validateRecordingExportRequest(request, availableStart, availableEnd)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val timestamps = report.videoFrameTimestampsMillis.ifEmpty {
            uniformFrameTimestamps(totalFrames, availableStart, availableEnd)
        }
        val target = File(localPath)
        (if (request.format == ClipFormat.PngSequence) target else target.parentFile)?.mkdirs()
        return when (request.format) {
            ClipFormat.Mp4 -> exportMp4(request, timestamps, target)
            ClipFormat.Gif -> exportGif(request, timestamps, target)
            ClipFormat.WebP -> exportWebP(request, timestamps, target)
            ClipFormat.PngSequence -> exportPngSequence(request, timestamps, target)
        }
    }

    private suspend fun decodeFrame(id: String, index: Int, scaleWidth: Int): BufferedImage? =
        bugs.loadBugVideoFrame(id, index)?.toScaledBufferedImage(scaleWidth)

    private suspend fun exportMp4(request: RecordingExportRequest, timestamps: List<Long>, target: File): ExportedClip {
        val indices = trimFrameIndices(timestamps, request.startMillis, request.endMillis)
        require(indices.isNotEmpty()) { "No frames in the selected trim range" }
        val first = decodeFrame(request.id, indices.first(), request.scale) ?: error("Could not decode video frames")
        val width = first.width
        val height = first.height
        val spanMillis = (timestamps[indices.last()] - timestamps[indices.first()]).coerceAtLeast(1L)
        val frameRate = if (indices.size >= 2) {
            (indices.size * 1000.0 / spanMillis).coerceIn(2.0, 60.0)
        } else {
            10.0
        }
        val recorder = FFmpegFrameRecorder(target, width, height)
        val converter = Java2DFrameConverter()
        var written = 0
        try {
            recorder.format = "mp4"
            recorder.frameRate = frameRate
            recorder.videoBitrate = 4_000_000
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            recorder.videoCodec = avcodec.AV_CODEC_ID_H264
            recorder.videoCodecName = "libopenh264"
            recorder.setOption("movflags", "+faststart")
            recorder.start()
            recorder.record(converter.convert(first))
            written++
            indices.drop(1).forEach { index ->
                val image = decodeFrame(request.id, index, request.scale) ?: return@forEach
                if (image.width != width || image.height != height) return@forEach
                recorder.record(converter.convert(image))
                written++
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            converter.close()
        }
        require(written > 0 && target.length() > 0L) { "MP4 trim produced no output" }
        return ExportedClip(target.absolutePath, ClipFormat.Mp4, target.length(), written, width, height)
    }

    private suspend fun exportGif(request: RecordingExportRequest, timestamps: List<Long>, target: File): ExportedClip {
        val indices = sampleFrameIndicesAtRate(timestamps, request.startMillis, request.endMillis, request.fps)
        require(indices.isNotEmpty()) { "No frames in the selected trim range" }
        val frames = indices.mapNotNull { index -> decodeFrame(request.id, index, request.scale) }
        require(frames.isNotEmpty()) { "Could not decode any frames for export" }
        val delays = frameDelaysCentiseconds(timestamps, indices)
        GifEncoder.write(target, frames, delays, request.loop)
        val first = frames.first()
        return ExportedClip(target.absolutePath, ClipFormat.Gif, target.length(), frames.size, first.width, first.height)
    }

    private suspend fun exportWebP(request: RecordingExportRequest, timestamps: List<Long>, target: File): ExportedClip {
        val indices = sampleFrameIndicesAtRate(timestamps, request.startMillis, request.endMillis, request.fps)
        require(indices.isNotEmpty()) { "No frames in the selected trim range" }
        val first = decodeFrame(request.id, indices.first(), request.scale) ?: error("Could not decode video frames")
        val width = first.width
        val height = first.height
        val recorder = FFmpegFrameRecorder(target, width, height)
        val converter = Java2DFrameConverter()
        var written = 0
        try {
            recorder.format = "webp"
            recorder.frameRate = request.fps.toDouble()
            recorder.videoCodec = avcodec.AV_CODEC_ID_WEBP
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            recorder.setOption("loop", if (request.loop) "0" else "1")
            recorder.setOption("lossless", "0")
            recorder.start()
            recorder.record(converter.convert(first))
            written++
            indices.drop(1).forEach { index ->
                val image = decodeFrame(request.id, index, request.scale) ?: return@forEach
                if (image.width != width || image.height != height) return@forEach
                recorder.record(converter.convert(image))
                written++
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            converter.close()
        }
        require(written > 0 && target.length() > 0L) {
            "WebP encoding failed (libwebp may be unavailable in this Andy build) — try GIF or MP4 instead"
        }
        return ExportedClip(target.absolutePath, ClipFormat.WebP, target.length(), written, width, height)
    }

    private suspend fun exportPngSequence(request: RecordingExportRequest, timestamps: List<Long>, target: File): ExportedClip {
        val indices = sampleFrameIndicesAtRate(timestamps, request.startMillis, request.endMillis, request.fps)
        require(indices.isNotEmpty()) { "No frames in the selected trim range" }
        target.mkdirs()
        var width = 0
        var height = 0
        var written = 0
        indices.forEachIndexed { position, frameIndex ->
            val image = decodeFrame(request.id, frameIndex, request.scale) ?: return@forEachIndexed
            width = image.width
            height = image.height
            val file = File(target, "frame-${(position + 1).toString().padStart(4, '0')}.png")
            ImageIO.write(image, "png", file)
            written++
        }
        require(written > 0) { "Could not decode any frames for export" }
        val totalBytes = target.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
            ?.sumOf { it.length() } ?: 0L
        return ExportedClip(target.absolutePath, ClipFormat.PngSequence, totalBytes, written, width, height)
    }

    /** Real per-frame intervals so GIF playback timing matches the source, not a flat rate. */
    private fun frameDelaysCentiseconds(timestamps: List<Long>, indices: List<Int>): List<Int> {
        if (indices.size <= 1) return listOf(DEFAULT_DELAY_CENTISECONDS)
        return indices.mapIndexed { position, frameIndex ->
            val next = indices.getOrNull(position + 1) ?: return@mapIndexed DEFAULT_DELAY_CENTISECONDS
            val deltaMillis = (timestamps[next] - timestamps[frameIndex]).coerceAtLeast(20L)
            (deltaMillis / 10L).toInt().coerceIn(2, 500)
        }
    }

    private fun MirrorFrame.toScaledBufferedImage(targetWidth: Int): BufferedImage {
        val source = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(0, 0, width, height, argb, 0, width)
        if (targetWidth <= 0 || targetWidth >= width) return source
        val targetHeight = (height.toLong() * targetWidth / width).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        graphics.dispose()
        return scaled
    }

    private companion object {
        const val DEFAULT_DELAY_CENTISECONDS = 8
    }
}
