package com.joetr.andy.mobile.ui

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.joetr.andy.mobile.data.vnc.Framebuffer
import kotlin.math.roundToInt

/** A viewport-resolution snapshot of the remote screen, plus the transform it was sampled with. */
class PresentedFrame(
    val image: ImageBitmap,
    val sampled: ViewMapping,
    val width: Int,
    val height: Int,
)

/**
 * Owns the one bitmap that actually reaches the GPU.
 *
 * [frame] is plain snapshot state read from the draw phase, so a new frame invalidates
 * drawing without recomposing the viewer — the previous code collected the frame counter
 * at composable scope, which recomposed the whole screen (chrome included) per frame.
 *
 * [present] is synchronized because a cancelled viewer effect can still be finishing a
 * background sample while a new one has already started (crop/viewport change on connect).
 * Without the lock, one call could resize the bitmap while another called
 * [Bitmap.setPixels] with the old dimensions — crashing with
 * `y + height must be <= bitmap.height()`.
 */
class RemoteFramePresenter {
    val frame: MutableState<PresentedFrame?> = mutableStateOf(null)

    private var bitmap: Bitmap? = null
    private var buffer = IntArray(0)
    private var width = 0
    private var height = 0

    @Synchronized
    fun clear() {
        frame.value = null
    }

    /** Sample [fb] for the current view transform. Safe to call from any thread. */
    @Synchronized
    fun present(fb: Framebuffer, mapping: ViewMapping, viewport: IntSize) {
        if (viewport.width <= 0 || viewport.height <= 0) return
        val w = viewport.width
        val h = viewport.height
        if (bitmap == null || w != width || h != height) {
            width = w
            height = h
            buffer = IntArray(w * h)
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val bmp = bitmap ?: return
        sampleFrame(fb.pixels, fb.width, fb.height, mapping, buffer, w, h)
        bmp.setPixels(buffer, 0, w, 0, 0, w, h)
        frame.value = PresentedFrame(bmp.asImageBitmap(), mapping, w, h)
    }
}

/**
 * Draw [frame] under the *current* transform. Pinch and pan keep moving at display rate
 * even while the sampler is catching up, because the delta between the sampled transform
 * and the live one is applied as a plain scaled blit.
 */
internal fun DrawScope.drawPresentedFrame(frame: PresentedFrame, current: ViewMapping) {
    if (frame.sampled.width < 1f || current.width < 1f) return
    val scale = current.width / frame.sampled.width
    val left = current.left - frame.sampled.left * scale
    val top = current.top - frame.sampled.top * scale
    drawImage(
        image = frame.image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(frame.width, frame.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(
            (frame.width * scale).roundToInt().coerceAtLeast(1),
            (frame.height * scale).roundToInt().coerceAtLeast(1),
        ),
    )
}
