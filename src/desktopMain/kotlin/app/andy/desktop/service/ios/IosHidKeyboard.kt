package app.andy.desktop.service.ios

/**
 * Maps Android [android.view.KeyEvent] key codes (what [app.andy.MirrorVideoSurface] emits as
 * [app.andy.service.MirrorInput.Key]) to USB HID keyboard usages (page 7) for SimulatorKit.
 */
internal fun androidKeyCodeToIosHidUsage(keyCode: Int): Int? = when (keyCode) {
    66 -> 0x28 // ENTER
    67 -> 0x2A // DEL / BACKSPACE
    112 -> 0x4C // FORWARD_DEL
    61 -> 0x2B // TAB
    111 -> 0x29 // ESCAPE
    19 -> 0x52 // DPAD_UP
    20 -> 0x51 // DPAD_DOWN
    21 -> 0x50 // DPAD_LEFT
    22 -> 0x4F // DPAD_RIGHT
    else -> null
}
