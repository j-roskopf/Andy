package app.andy.ui.live

import app.andy.service.MirrorInput

/**
 * Rescales pixel coordinates in [input] from a [from] source size to a [to] source size,
 * preserving the relative (normalized) position. Used for the Live grid's synchronized-input
 * mode (§C.4), where a tap recorded against one target's stream must land at the same relative
 * point on every other target, whatever its own resolution.
 *
 * Non-coordinate inputs (key events, text, hardware buttons) pass through unchanged.
 */
internal fun scaleMirrorInput(input: MirrorInput, from: MirrorSourceSize, to: MirrorSourceSize): MirrorInput {
    if (from.width <= 0 || from.height <= 0 || from == to) return input
    fun scaleX(x: Int): Int = ((x.toLong() * to.width) / from.width).toInt()
    fun scaleY(y: Int): Int = ((y.toLong() * to.height) / from.height).toInt()
    return when (input) {
        is MirrorInput.Tap -> input.copy(x = scaleX(input.x), y = scaleY(input.y))
        is MirrorInput.Touch -> input.copy(x = scaleX(input.x), y = scaleY(input.y))
        is MirrorInput.Swipe -> input.copy(
            startX = scaleX(input.startX),
            startY = scaleY(input.startY),
            endX = scaleX(input.endX),
            endY = scaleY(input.endY),
        )
        is MirrorInput.Key,
        is MirrorInput.Text,
        MirrorInput.Back,
        MirrorInput.Home,
        MirrorInput.Recents,
        MirrorInput.Power,
        -> input
    }
}
