package app.andy.ui.live

import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuFixedConfig
import kotlin.math.min

/** Letterboxed destination for fitting [srcW]×[srcH] into [dstW]×[dstH]. */
internal data class DhuFitRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Pure layout helpers for embedded DHU frames: strip host window chrome and aspect-fit
 * into the Live pane (mirrors how the phone mirror host preserves AR).
 */
internal object DhuFrameLayout {
    /**
     * DHU's SDL window includes OS chrome above the configured [DhuFixedConfig] content.
     * `screencapture -l` / similar APIs capture the full window, which stretches wrong if we
     * treat chrome as part of the Auto surface.
     */
    fun cropWindowChrome(
        frame: DhuCaptureFrame,
        contentWidth: Int = DhuFixedConfig.Width,
        contentHeight: Int = DhuFixedConfig.Height,
    ): DhuCaptureFrame {
        if (frame.width < 2 || frame.height < 2 || contentWidth < 1 || contentHeight < 1) return frame
        val expectedContentHeight = ((frame.width.toLong() * contentHeight) / contentWidth).toInt()
        val chrome = frame.height - expectedContentHeight
        // Title-bar sized strip only; ignore unrelated aspect mismatches.
        if (chrome !in 1..160) return frame
        val width = frame.width
        val height = expectedContentHeight
        val pixels = IntArray(width * height)
        val srcOffset = chrome * width
        frame.argb.copyInto(pixels, destinationOffset = 0, startIndex = srcOffset, endIndex = srcOffset + pixels.size)
        return DhuCaptureFrame(width, height, pixels, frame.frameNumber)
    }

    fun fitRect(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): DhuFitRect {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return DhuFitRect(0, 0, dstWidth.coerceAtLeast(1), dstHeight.coerceAtLeast(1))
        }
        val scale = min(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
        val width = (srcWidth * scale).toInt().coerceAtLeast(1)
        val height = (srcHeight * scale).toInt().coerceAtLeast(1)
        return DhuFitRect(
            x = (dstWidth - width) / 2,
            y = (dstHeight - height) / 2,
            width = width,
            height = height,
        )
    }

    /** Map a view-pixel pointer into 0..1 content coordinates inside a letterboxed [fit]. */
    fun normalizePointer(
        viewX: Float,
        viewY: Float,
        fit: DhuFitRect,
    ): Pair<Float, Float> {
        if (fit.width <= 0 || fit.height <= 0) return 0.5f to 0.5f
        val nx = ((viewX - fit.x) / fit.width.toFloat()).coerceIn(0f, 1f)
        val ny = ((viewY - fit.y) / fit.height.toFloat()).coerceIn(0f, 1f)
        return nx to ny
    }
}
