package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.AccessibilityNode
import app.andy.service.AccessibilityService

class DesktopAccessibilityService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : AccessibilityService {
    override suspend fun dump(serial: String): AccessibilityNode? {
        val adb = devices.adbPath() ?: return null
        runner.run(listOf(adb, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"), 10)
        val result = runner.run(listOf(adb, "-s", serial, "exec-out", "cat", "/sdcard/window_dump.xml"), 10)
        return runCatching { AndroidParsers.parseAccessibilityXml(result.stdout) }.getOrNull()
    }
}
