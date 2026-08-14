package app.andy.terminal.rust

import kotlin.math.abs

/**
 * SGR / X10 mouse report encoding for the Rust canvas pointer path.
 *
 * Button codes match xterm: 0=left, 1=middle, 2=right, 64/65=wheel, +32=motion.
 */
internal object RustTerminalMouse {
    const val BUTTON_LEFT = 0
    const val BUTTON_MIDDLE = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MOVE = 32
    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65

    fun sgrReport(button: Int, col: Int, row: Int, pressed: Boolean): ByteArray {
        val c = col.coerceAtLeast(1)
        val r = row.coerceAtLeast(1)
        val suffix = if (pressed) 'M' else 'm'
        return "\u001B[<$button;$c;$r$suffix".toByteArray(Charsets.UTF_8)
    }

    fun x10Report(button: Int, col: Int, row: Int): ByteArray {
        val b = (button + 32).coerceIn(32, 255)
        val c = (col.coerceAtLeast(1) + 32).coerceIn(32, 255)
        val r = (row.coerceAtLeast(1) + 32).coerceIn(32, 255)
        return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), b.toByte(), c.toByte(), r.toByte())
    }

    fun encodeClick(
        flags: Int,
        button: Int,
        col: Int,
        row: Int,
        pressed: Boolean,
    ): ByteArray? {
        if (flags and RustMouseFlags.REPORTING == 0) return null
        val cellCol = col + 1
        val cellRow = row + 1
        return if (flags and RustMouseFlags.SGR != 0) {
            sgrReport(button, cellCol, cellRow, pressed)
        } else if (pressed) {
            x10Report(button, cellCol, cellRow)
        } else {
            null
        }
    }

    fun encodeMove(flags: Int, col: Int, row: Int, dragging: Boolean): ByteArray? {
        if (flags and RustMouseFlags.REPORTING == 0) return null
        val wantMotion = flags and RustMouseFlags.MOTION != 0
        val wantDrag = flags and RustMouseFlags.DRAG != 0
        if (!wantMotion && !(wantDrag && dragging)) return null
        val button = BUTTON_MOVE or if (dragging) BUTTON_LEFT else 3
        return encodeClick(flags, button, col, row, pressed = true)
    }
}

/** Accumulates fractional trackpad deltas into wheel steps (same idea as [TmuxWheelInput]). */
internal class RustWheelAccumulator(
    private val writeBytes: (ByteArray) -> Unit,
) {
    private var accumulated = 0f

    fun onScroll(deltaY: Float, flags: Int, col: Int, row: Int): Boolean {
        if (deltaY == 0f) return false
        accumulated = (accumulated + deltaY * 3f).coerceIn(-8f, 8f)
        val steps = accumulated.toInt()
        if (steps == 0) return true
        accumulated -= steps
        val button = if (steps > 0) RustTerminalMouse.WHEEL_DOWN else RustTerminalMouse.WHEEL_UP
        val report = RustTerminalMouse.encodeClick(flags, button, col, row, pressed = true)
            ?: return false
        val bytes = ByteArray(report.size * abs(steps))
        repeat(abs(steps)) { i ->
            System.arraycopy(report, 0, bytes, i * report.size, report.size)
        }
        writeBytes(bytes)
        return true
    }
}
