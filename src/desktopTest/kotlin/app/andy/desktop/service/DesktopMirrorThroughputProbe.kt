package app.andy.desktop.service

import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Measures live-frame throughput while the device screen is continuously animating.
 * The emulator gRPC streamScreenshot stream is change-driven, so a static screen
 * legitimately yields near-zero fps; this probe forces sustained motion (fling
 * scrolling in Settings) and reports the peak rolling fps the pipeline delivers.
 *
 * Gated behind ANDY_DEVICE_SMOKE=1 so it never runs in CI.
 */
class DesktopMirrorThroughputProbe {
    @Test
    fun measuresThroughputUnderMotion() = runBlocking {
        if (System.getenv("ANDY_DEVICE_SMOKE") != "1") return@runBlocking
        val services = createDesktopServices()
        val serial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
            ?: services.devices.listDevices().first { it.state.name == "Online" }.serial

        val connect = services.mirror.connect(serial, MirrorVideoConfig(maxSize = 720))
        check(connect.isSuccess) { connect.stderr.ifBlank { connect.stdout } }
        try {
            // Foreground a long, scrollable surface.
            services.devices.shell(serial, listOf("am", "start", "-a", "android.settings.SETTINGS"))
            var last: MirrorFrame = withTimeout(12_000) {
                services.mirror.frames.first { it.width > 1 && it.height > 1 && it.frameNumber > 0 }
            }

            // Device-side continuous fling scrolling — guaranteed to animate regardless of input path.
            val motion = launch(Dispatchers.IO) {
                var down = true
                while (isActive) {
                    val (y1, y2) = if (down) 1600 to 400 else 400 to 1600
                    services.devices.shell(serial, listOf("input", "swipe", "540", y1.toString(), "540", y2.toString(), "120"))
                    down = !down
                }
            }

            var frames = 0L
            var peakRollingFps = 0f
            val start = System.nanoTime()
            val windowStart = longArrayOf(System.nanoTime())
            var windowFrames = 0L
            try {
                val deadline = System.nanoTime() + 6_000_000_000L
                while (System.nanoTime() < deadline) {
                    val next = withTimeout(3_000) {
                        services.mirror.frames.first { it.frameNumber > last.frameNumber }
                    }
                    frames += next.frameNumber - last.frameNumber
                    last = next
                    next.decodedFps?.let { peakRollingFps = maxOf(peakRollingFps, it) }
                    windowFrames++
                    val now = System.nanoTime()
                    if (now - windowStart[0] >= 1_000_000_000L) {
                        val fps = windowFrames * 1_000_000_000f / (now - windowStart[0])
                        peakRollingFps = maxOf(peakRollingFps, fps)
                        windowStart[0] = now
                        windowFrames = 0
                    }
                }
            } finally {
                motion.cancel()
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            val avgFps = frames / elapsed
            writePng(last, File("build/reports/andy-mirror-smoke/motion-frame.png"))
            println("THROUGHPUT avgFps=%.1f peakRollingFps=%.1f totalFrames=%d elapsed=%.1fs".format(avgFps, peakRollingFps, frames, elapsed))
        } finally {
            services.mirror.disconnect()
        }
    }

    @Test
    fun grpcTouchInputChangesTheScreen() = runBlocking {
        if (System.getenv("ANDY_DEVICE_SMOKE") != "1") return@runBlocking
        val services = createDesktopServices()
        val serial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
            ?: services.devices.listDevices().first { it.state.name == "Online" }.serial
        check(services.mirror.connect(serial, MirrorVideoConfig(maxSize = 720)).isSuccess)
        try {
            services.devices.shell(serial, listOf("am", "start", "-a", "android.settings.SETTINGS"))
            delay(1500)
            val before = withTimeout(12_000) {
                services.mirror.frames.first { it.width > 1 && it.height > 1 && it.frameNumber > 0 }
            }
            val beforeSig = signature(before)
            // Inject a scroll purely through the app's gRPC input path.
            val h = before.height
            services.mirror.sendInput(MirrorInput.Swipe(360, (h * 0.8f).toInt(), 360, (h * 0.2f).toInt(), 150))
            val changed = withTimeout(4_000) {
                services.mirror.frames.first { it.frameNumber > before.frameNumber && signature(it) != beforeSig }
            }
            writePng(changed, File("build/reports/andy-mirror-smoke/after-grpc-input.png"))
            println("GRPC-INPUT screen changed after gRPC swipe: beforeSig=$beforeSig afterSig=${signature(changed)}")
        } finally {
            services.mirror.disconnect()
        }
    }

    @Test
    fun keyboardTypesIntoFocusedField() = runBlocking {
        if (System.getenv("ANDY_DEVICE_SMOKE") != "1") return@runBlocking
        val services = createDesktopServices()
        val serial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
            ?: services.devices.listDevices().first { it.state.name == "Online" }.serial
        check(services.mirror.connect(serial, MirrorVideoConfig(maxSize = 720)).isSuccess)
        try {
            // This activity opens with its search EditText focused and the soft keyboard up.
            services.devices.shell(serial, listOf("am", "start", "-a", "android.settings.APP_SEARCH_SETTINGS"))
            delay(2500)
            withTimeout(12_000) {
                services.mirror.frames.first { it.width > 1 && it.height > 1 && it.frameNumber > 0 }
            }
            writePng(latest(services), File("build/reports/andy-mirror-smoke/kbd-after-tap.png"))

            val text = "bluetooth"
            val start = System.nanoTime()
            for (ch in text) services.mirror.sendInput(MirrorInput.Text(ch.toString()))
            val typeMs = (System.nanoTime() - start) / 1_000_000
            delay(1200)
            writePng(latest(services), File("build/reports/andy-mirror-smoke/kbd-after-type.png"))
            // Backspace one character via the named-key path.
            services.mirror.sendInput(MirrorInput.Key(67))
            delay(800)
            writePng(latest(services), File("build/reports/andy-mirror-smoke/kbd-after-backspace.png"))
            println("KEYBOARD typed '${text}' (${text.length} chars) in ${typeMs}ms")
        } finally {
            services.mirror.disconnect()
        }
    }

    private suspend fun latest(services: app.andy.service.AndyServices): MirrorFrame =
        withTimeout(4_000) { services.mirror.frames.first { it.width > 1 && it.height > 1 } }

    // Cheap content fingerprint from sampled pixels; enough to detect a scroll.
    private fun signature(frame: MirrorFrame): Int {
        var acc = 0
        val step = (frame.argb.size / 512).coerceAtLeast(1)
        var i = 0
        while (i < frame.argb.size) {
            acc = acc * 31 + frame.argb[i]
            i += step
        }
        return acc
    }

    private fun writePng(frame: MirrorFrame, output: File) {
        output.parentFile.mkdirs()
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
        ImageIO.write(image, "png", output)
    }
}
