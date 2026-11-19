package app.andy.ui.controls

import app.andy.model.AndroidDisplay
import app.andy.model.parseAndroidDisplays
import app.andy.model.parseAndroidDisplaysFromDumpsysDisplay
import app.andy.service.DeviceService

/**
 * Logical displays exposed by an Android device.
 *
 * Dual-screen handhelds (AYN Thor and similar) publish the second panel as another logical
 * display. Each one can be captured by its own scrcpy session via `display_id`.
 *
 * `dumpsys display` is preferred because it includes each display's type, letting callers skip
 * app-created virtual displays (such as scrcpy's own) that are not real panels. Older devices
 * without that section fall back to `dumpsys window displays`.
 */
suspend fun DeviceService.readAndroidDisplays(serial: String): List<AndroidDisplay> {
    val typed = shell(serial, listOf("dumpsys", "display"))
    parseAndroidDisplaysFromDumpsysDisplay(typed.stdout.ifBlank { typed.stderr })
        .takeIf { it.isNotEmpty() }
        ?.let { return it }
    val result = shell(serial, listOf("dumpsys", "window", "displays"))
    return parseAndroidDisplays(result.stdout.ifBlank { result.stderr })
}
