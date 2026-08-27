package app.andy.model

/**
 * A single screenshot edit, in coordinates normalized to `[0, 1]` over the base image so the
 * same annotation renders correctly whether drawn on a scaled-down preview canvas or baked into
 * the full-resolution PNG (§E.5). [Redaction] paints an opaque block (privacy blackout);
 * [Box]/[Arrow]/[Freehand]/[TextNote] are visible annotations.
 */
sealed interface ScreenshotAnnotation {
    data class Redaction(val left: Float, val top: Float, val right: Float, val bottom: Float) : ScreenshotAnnotation
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) : ScreenshotAnnotation
    data class Arrow(val startX: Float, val startY: Float, val endX: Float, val endY: Float) : ScreenshotAnnotation
    /** Flattened `x0, y0, x1, y1, ...` points of a freehand stroke. */
    data class Freehand(val points: List<Float>) : ScreenshotAnnotation
    data class TextNote(val x: Float, val y: Float, val text: String) : ScreenshotAnnotation
}

/** The full set of edits to bake into a captured screenshot before saving. */
data class ScreenshotEdits(
    val annotations: List<ScreenshotAnnotation> = emptyList(),
    val deviceFrame: Boolean = false,
)
