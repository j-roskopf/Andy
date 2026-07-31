package app.andy.terminal

import kotlin.math.abs

/**
 * Sends wheel events to an attached tmux client using its SGR mouse protocol.
 *
 * BossTerm 1.2.143 recognizes tmux's mouse mode but its `mouseWheelMoved` path does not
 * produce an effective event for tmux. Keep this adapter byte-local to the existing PTY:
 * no tmux process is forked and fractional macOS trackpad deltas are accumulated.
 */
internal class TmuxWheelInput(
    private val writeBytes: (ByteArray) -> Unit,
) {
    private var accumulatedDelta = 0f

    fun onScroll(deltaY: Float): Boolean {
        if (deltaY == 0f) return false
        accumulatedDelta = (accumulatedDelta + deltaY * SCROLL_MULTIPLIER)
            .coerceIn(-MAX_STEPS_PER_EVENT.toFloat(), MAX_STEPS_PER_EVENT.toFloat())
        val steps = accumulatedDelta.toInt()
        if (steps == 0) return true
        accumulatedDelta -= steps

        val button = if (steps > 0) WHEEL_DOWN else WHEEL_UP
        val report = "\u001B[<$button;1;1M"
        writeBytes(buildString(report.length * abs(steps)) {
            repeat(abs(steps)) { append(report) }
        }.encodeToByteArray())
        return true
    }

    private companion object {
        private const val WHEEL_UP = 64
        private const val WHEEL_DOWN = 65
        private const val SCROLL_MULTIPLIER = 3f
        private const val MAX_STEPS_PER_EVENT = 8
    }
}
