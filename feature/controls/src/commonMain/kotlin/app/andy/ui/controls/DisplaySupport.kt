package app.andy.ui.controls

import app.andy.model.AndroidDisplay
import app.andy.model.parseAndroidDisplays
import app.andy.service.DeviceService

/**
 * Logical displays exposed by an Android device.
 *
 * Dual-screen handhelds (AYN Thor and similar) publish the second panel as another logical
 * display. Each one can be captured by its own scrcpy session via `display_id`.
 */
suspend fun DeviceService.readAndroidDisplays(serial: String): List<AndroidDisplay> {
    val result = shell(serial, listOf("dumpsys", "window", "displays"))
    return parseAndroidDisplays(result.stdout.ifBlank { result.stderr })
}
