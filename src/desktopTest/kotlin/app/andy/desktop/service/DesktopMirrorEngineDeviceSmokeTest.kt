package app.andy.desktop.service

import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopMirrorEngineDeviceSmokeTest {
    @Test
    fun embeddedMirrorDecodesConnectedDeviceFrames() = runBlocking {
        if (System.getenv("ANDY_DEVICE_SMOKE") != "1") return@runBlocking

        val services = createDesktopServices()
        val requestedSerial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
        val device = services.devices.listDevices().firstOrNull {
            it.state.name == "Online" && (requestedSerial == null || it.serial == requestedSerial)
        }
            ?: error("No online Android device connected")

        val maxSize = System.getenv("ANDY_DEVICE_SMOKE_MAX_SIZE")?.toIntOrNull()
        val result = services.mirror.connect(
            device.serial,
            maxSize?.let { MirrorVideoConfig(maxSize = it) } ?: MirrorVideoConfig(),
        )
        assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })

        val statuses = mutableListOf<String>()
        val statusJob = launch {
            services.mirror.status.collect { status ->
                statuses += status
                println("mirror-status: $status")
            }
        }
        var firstFrame: MirrorFrame? = null
        var lastFrame: MirrorFrame
        var streamingStartedAt = 0L
        try {
            firstFrame = try {
                withTimeout(12_000) {
                    services.mirror.frames.first { it.width > 1 && it.height > 1 && it.frameNumber > 0 }
                }
            } catch (error: Throwable) {
                error("Timed out waiting for first mirror frame. Status trail: ${statuses.joinToString(" | ")}")
            }
            writeFramePng(firstFrame, File("build/reports/andy-mirror-smoke/first-frame.png"))
            lastFrame = firstFrame
            // Connection, device encoder startup, and first-frame decode are not presentation
            // throughput. Measure the active stream below so a valid CPU fallback is not
            // rejected merely because an emulator took time to start its encoder.
            streamingStartedAt = System.nanoTime()
            val motionJob = launch {
                while (isActive) {
                    services.mirror.sendInput(MirrorInput.Swipe(900, 1400, 180, 1400, 250))
                    delay(700)
                    services.mirror.sendInput(MirrorInput.Swipe(180, 1400, 900, 1400, 250))
                    delay(700)
                }
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            try {
                while (System.nanoTime() < deadline) {
                    lastFrame = withTimeout(3_000) {
                        services.mirror.frames.first { it.frameNumber > lastFrame.frameNumber }
                    }
                }
            } finally {
                motionJob.cancel()
            }
        } finally {
            statusJob.cancel()
            services.mirror.disconnect()
        }

        val elapsedSeconds = (System.nanoTime() - streamingStartedAt) / 1_000_000_000.0
        val decodedFps = (lastFrame.frameNumber - firstFrame.frameNumber).coerceAtLeast(0) / elapsedSeconds
        println("mirror-smoke decodedFps=%.1f firstFrame=${firstFrame.frameNumber} lastFrame=${lastFrame.frameNumber}".format(decodedFps))
        assertTrue(decodedFps >= 10.0, "Expected at least 10 decoded fps, got %.1f fps".format(decodedFps))
        assertTrue(lastFrame.decodedFps != null && lastFrame.decodedFps >= 1f, "Expected in-app decoded FPS telemetry, got ${lastFrame.decodedFps}")
    }

    private fun writeFramePng(frame: MirrorFrame?, output: File) {
        requireNotNull(frame) { "No decoded frame available" }
        output.parentFile.mkdirs()
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
        ImageIO.write(image, "png", output)
    }
}
