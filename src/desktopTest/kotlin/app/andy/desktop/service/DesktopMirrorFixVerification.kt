package app.andy.desktop.service

import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the Stop/tap/fling fixes against a live emulator. Gated behind
 * ANDY_DEVICE_SMOKE=1 so it never runs in CI.
 */
class DesktopMirrorFixVerification {
    @Test
    fun disconnectClearsFrameAndCompletedGestureLeavesTapsWorking() = runBlocking {
        if (System.getenv("ANDY_DEVICE_SMOKE") != "1") return@runBlocking
        val services = createDesktopServices()
        val serial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
            ?: services.devices.listDevices().first { it.state.name == "Online" }.serial
        check(services.mirror.connect(serial, MirrorVideoConfig(maxSize = 720)).isSuccess)

        val wm = services.devices.shell(serial, listOf("wm", "size")).stdout
        val (dispW, dispH) = Regex("""(\d+)x(\d+)""").find(wm)!!.destructured.let { it.component1().toInt() to it.component2().toInt() }
        val frameW = (720.0 * dispW / dispH).toInt(); val frameH = 720
        fun fx(x: Int) = x * frameW / dispW
        fun fy(y: Int) = y * frameH / dispH

        suspend fun down(x: Int, y: Int) = services.mirror.sendInput(MirrorInput.Touch(MirrorTouchAction.Down, x, y))
        suspend fun move(x: Int, y: Int) = services.mirror.sendInput(MirrorInput.Touch(MirrorTouchAction.Move, x, y))
        suspend fun up(x: Int, y: Int) = services.mirror.sendInput(MirrorInput.Touch(MirrorTouchAction.Up, x, y))
        suspend fun screen(): String {
            val out = services.devices.shell(serial, listOf("dumpsys", "activity", "activities")).stdout
            val resumed = Regex("""mResumedActivity.*\{[^}]*\s(\S+/\S+)""").find(out)?.groupValues?.getOrNull(1)
            val top = Regex("""topResumedActivity=\S+\{[^}]*\s(\S+/\S+)""").find(out)?.groupValues?.getOrNull(1)
            return resumed ?: top ?: "unknown"
        }
        suspend fun openSettings() {
            services.devices.shell(serial, listOf("am", "force-stop", "com.android.settings"))
            services.devices.shell(serial, listOf("am", "start", "-a", "android.settings.SETTINGS"))
            delay(2500)
        }
        suspend fun rowCenter(): Pair<Int, Int> {
            services.devices.shell(serial, listOf("uiautomator", "dump"))
            val xml = services.devices.shell(serial, listOf("cat", "/sdcard/window_dump.xml")).stdout
            val (l, t, r, b) = Regex("""clickable="true"[^>]*bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""").findAll(xml)
                .map { it.destructured }
                .first { (l, t, _, b) -> (b.toInt() - t.toInt()) > 60 && (t.toInt() + b.toInt()) / 2 in (dispH / 5)..(dispH * 4 / 5) }
            return (l.toInt() + r.toInt()) / 2 to (t.toInt() + b.toInt()) / 2
        }
        suspend fun tapNavigates(dx: Int, dy: Int): Boolean {
            openSettings()
            val home = screen()
            down(fx(dx), fy(dy)); up(fx(dx), fy(dy))
            delay(1500)
            val after = screen()
            if (after != home) services.devices.shell(serial, listOf("input", "keyevent", "4"))
            return after != home
        }

        try {
            openSettings()
            val (dx, dy) = rowCenter()

            // A completed drag gesture that ends off the target (like a fling whose Up
            // now always fires, clamped) must NOT wedge later taps.
            openSettings()
            down(fx(dispW / 2), fy((dispH * 0.7f).toInt()))
            for (i in 1..8) { move(fx(dispW / 2), fy((dispH * (0.7f - i * 0.06f)).toInt())); delay(16) }
            up(fx(dispW / 2), fy((dispH * 0.2f).toInt()))
            delay(600)
            val tapWorksAfterFling = tapNavigates(dx, dy)
            println("FIXVERIFY tap works after completed fling = $tapWorksAfterFling")
            assertTrue(tapWorksAfterFling, "tap should work after a fully-released fling")

            // Frozen-frame fix: disconnect resets the stream to the 1x1 sentinel.
            services.mirror.disconnect()
            val cleared = withTimeout(3_000) { services.mirror.frames.first { it.width <= 1 && it.height <= 1 } }
            println("FIXVERIFY frame after disconnect = ${cleared.width}x${cleared.height}")
            assertTrue(cleared.width <= 1 && cleared.height <= 1, "disconnect should clear the frame")
        } finally {
            services.mirror.disconnect()
        }
    }
}
