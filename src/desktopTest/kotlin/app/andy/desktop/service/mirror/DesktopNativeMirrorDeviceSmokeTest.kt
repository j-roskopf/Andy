package app.andy.desktop.service.mirror

import app.andy.MirrorVideoSurface
import app.andy.desktop.service.DesktopDeviceService
import app.andy.desktop.service.createDesktopServices
import app.andy.model.DeviceConnectionState
import app.andy.service.MirrorFrame
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import java.awt.BorderLayout
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun batterySaverIsEnabled(settingsValue: String): Boolean = settingsValue.trim() == "1"
private const val TARGET_DISPLAY_FPS = 60f
// The rolling one-second counter is sampled between two native completion callbacks. A 60 Hz
// presenter can therefore report 59.99 even when it has not dropped a frame; keep acceptance
// strict enough to reject a real 59/58 Hz mode without failing on timer quantization.
private const val MIN_TARGET_DISPLAY_FPS = 59.5f

class DesktopNativeMirrorDeviceSmokeTest {
    @Test
    fun batterySaverParserOnlyAcceptsTheEnabledSystemValue() {
        assertTrue(batterySaverIsEnabled("1\n"))
        assertFalse(batterySaverIsEnabled("0\n"))
        assertFalse(batterySaverIsEnabled("null\n"))
    }

    @Test
    fun videoToolboxMetalPresentsFramesFromConnectedDevice() = runBlocking {
        if (System.getenv("ANDY_DEVICE_NATIVE_SMOKE") != "1") return@runBlocking
        val useGpuHub = GpuMirrorJni.isAvailable()
        if (!useGpuHub && !NativeMirrorJni.isAvailable()) return@runBlocking
        // The in-process JAWT route is the supported accelerated path once Metal presents into
        // the realized Compose host (verified by the screen-capture check below).
        if (!useGpuHub && !NativeMirrorJni.isEmbeddedPresentationSupported()) return@runBlocking

        val services = createDesktopServices()
        val requestedSerial = System.getenv("ANDY_DEVICE_SERIAL")?.takeIf { it.isNotBlank() }
        val device = services.devices.listDevices().firstOrNull {
            it.state == DeviceConnectionState.Online && (requestedSerial == null || it.serial == requestedSerial)
        } ?: error("No requested online Android device")
        val streamKey = device.serial

        lateinit var frame: JFrame
        val metadataFrames = MutableStateFlow(MirrorFrame(2, 2, IntArray(0), frameNumber = 1))
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frames = metadataFrames,
                        resetKey = streamKey,
                        modifier = Modifier.fillMaxSize(),
                        nativePresentation = true,
                        gpuMirrorStreamKey = streamKey.takeIf { useGpuHub },
                    )
                }
            }
            frame = JFrame("Andy native mirror smoke")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(332, 720)
            frame.isVisible = true
        }

        val host = if (useGpuHub) {
            // Pipeline is created on connect; surface attaches once the session key exists.
            // Pre-create so Compose can register the presenter before scrcpy starts.
            assertNotNull(GpuMirrorSessions.acquire(streamKey))
            assertNotNull(awaitGpuMirrorHost(5_000), "Compose Live surface did not realize a GPU hub host")
        } else {
            val hostDeadline = System.nanoTime() + 5_000_000_000L
            var legacyHost = NativeMirrorHostRegistry.current()
            while (legacyHost == null && System.nanoTime() < hostDeadline) {
                Thread.sleep(20)
                legacyHost = NativeMirrorHostRegistry.current()
            }
            assertTrue(legacyHost != null, "Compose Live surface did not realize a native JAWT host")
            legacyHost!!
        }
        if (System.getenv("ANDY_REQUIRE_MIRROR_TARGET") == "1") {
            val batterySaver = services.devices.shell(device.serial, listOf("settings", "get", "global", "low_power"))
            assertTrue(
                !batterySaverIsEnabled(batterySaver.stdout),
                "Physical target verification requires Battery Saver off; current device reports global low_power=${batterySaver.stdout.trim()}",
            )
        }
        var probeTrigger: ProbeSocketTrigger? = null
        try {
            services.devices.shell(
                device.serial,
                listOf("am", "force-stop", "app.andy.latencyprobe"),
            )
            val probeStarted = services.devices.shell(
                device.serial,
                listOf("am", "start", "-n", "app.andy.latencyprobe/.LatencyProbeActivity"),
            )
            assertTrue(probeStarted.isSuccess, "Install the andy latency-probe fixture before native device validation")
            delay(500)
            val probeRegion = latencyProbeRegion(services, device.serial)
            val bitRate = System.getenv("ANDY_DEVICE_MIRROR_BIT_RATE")
                ?.toIntOrNull()
                ?.coerceIn(250_000, 20_000_000)
                ?: 4_000_000
            val maxFps = System.getenv("ANDY_DEVICE_MIRROR_MAX_FPS")
                ?.toIntOrNull()
                ?.coerceIn(0, 240)
                ?: 60
            val result = services.mirror.connect(
                device.serial,
                MirrorVideoConfig(
                    maxSize = 720,
                    bitRate = bitRate,
                    maxFps = maxFps,
                    rendererMode = MirrorRendererMode.Accelerated,
                ),
            )
            assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })
            // Native code samples the fixture's fixed 24 dp, 120 dp, 192 dp square directly
            // from the decoded Y plane. Resolve it from the connected device's actual size and
            // density so the test does not assume a particular phone.
            NativeMirrorJni.configureLatencyProbe(
                left = probeRegion.left,
                top = probeRegion.top,
                width = probeRegion.width,
                height = probeRegion.height,
            )
            delay(1_000) // establish the dark probe baseline before injecting the first tap
            assertTrue(
                mirrorHostContainsNonBlackPixels(host),
                "Metal completed frames but the realized Compose/JAWT host remained black before touch",
            )
            val triggerMode = System.getenv("ANDY_DEVICE_MIRROR_LATENCY_TRIGGER")
            if (triggerMode == "probe_socket" && System.getenv("ANDY_REQUIRE_MIRROR_TARGET") == "1") {
                error("The probe_socket trigger measures capture/encode only and cannot satisfy the input-to-present acceptance gate")
            }
            if (triggerMode == "probe_socket") {
                probeTrigger = openProbeSocket(services.devices as DesktopDeviceService, device.serial)
            }
            val trigger = probeTrigger
            repeat(6) {
                if (trigger != null) {
                    // This diagnostic bypasses Android input dispatch and measures the capture,
                    // encode, transport, decode, and native-present floor only. It is opt-in so
                    // the normal acceptance gate remains host-input-to-present.
                    NativeMirrorJni.recordInput()
                    trigger.pulse()
                } else {
                    val input = services.mirror.sendInput(MirrorInput.Tap(166, 360))
                    assertTrue(input.isSuccess, input.stderr.ifBlank { input.stdout })
                }
                delay(700)
                assertTrue(
                    mirrorHostContainsNonBlackPixels(host),
                    "Android Live host went black after touch/tap #$it — Metal lost z-order or stopped presenting",
                )
            }
            delay(1_500) // allow the last native stats window to publish
            assertTrue(
                mirrorHostContainsNonBlackPixels(host),
                "Metal completed frames but the realized Compose/JAWT host remained black",
            )
            val session = services.mirror.session.value ?: error("No native mirror session")
            assertEquals(MirrorBackendKind.NativeHardware, session.backend.kind, session.failureReason)
            val presented = if (useGpuHub) {
                GpuMirrorSessions.get(streamKey)?.framesPresented() ?: 0L
            } else {
                NativeMirrorJni.framesPresented()
            }
            // `framesPresented / wholeTestDuration` includes the deliberate between-tap and
            // post-tap settling delays. The renderer already publishes a rolling presentation
            // rate, which is the relevant 60 FPS acceptance signal.
            val displayedFps = session.stats.displayedFps
            val p95 = NativeMirrorJni.p95InputToPresentMillis()
            val inputSamples = NativeMirrorJni.inputToPresentSamplesMillis()
            val packetToPresent = NativeMirrorJni.p95PacketToPresentMillis()
            val transportToPresent = NativeMirrorJni.p95TransportToPresentMillis()
            val transitions = NativeMirrorJni.latencyProbeTransitions()
            println("native-mirror trigger=${triggerMode ?: "scrcpy_control"} bitrate=$bitRate maxFps=$maxFps presented=$presented displayedFps=%.1f probeTransitions=$transitions inputP95=$p95 inputSamples=$inputSamples packetP95=$packetToPresent transportP95=$transportToPresent status=${session.stats}".format(displayedFps))
            assertTrue(presented > 0, "Expected at least one Metal-presented frame")
            assertTrue(transitions > 0, "Expected decoded frames to observe the latency probe transition")
            assertTrue(p95 != null, "Expected at least one host-input-to-visible-probe latency sample")
            assertNotNull(NativeMirrorJni.inspectPixel(.1f, .1f), "Expected a native decoded-pixel inspection result")
            if (System.getenv("ANDY_REQUIRE_MIRROR_TARGET") == "1") {
                assertTrue(displayedFps >= MIN_TARGET_DISPLAY_FPS, "Expected $TARGET_DISPLAY_FPS displayed FPS, got $displayedFps")
                assertTrue(p95 <= 50f, "Expected <=50 ms P95 latency, got $p95")
            }
        } finally {
            probeTrigger?.close()
            services.mirror.disconnect()
            if (useGpuHub) GpuMirrorSessions.release(streamKey)
            SwingUtilities.invokeAndWait {
                frame.dispose()
            }
        }
    }

    private suspend fun latencyProbeRegion(
        services: app.andy.service.AndyServices,
        serial: String,
    ): ProbeRegion {
        val size = services.devices.shell(serial, listOf("wm", "size"))
        val density = services.devices.shell(serial, listOf("wm", "density"))
        assertTrue(size.isSuccess && density.isSuccess, "Unable to read device display metrics for latency probe")
        val dimensions = Regex("""(?:Physical|Override) size:\s*(\d+)x(\d+)""")
            .find(size.stdout)
            ?: error("Unable to parse device size: ${size.stdout}")
        val densityDpi = Regex("""(?:Physical|Override) density:\s*(\d+)""")
            .find(density.stdout)
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull()
            ?: error("Unable to parse device density: ${density.stdout}")
        val widthPx = dimensions.groupValues[1].toFloat()
        val heightPx = dimensions.groupValues[2].toFloat()
        val pxPerDp = densityDpi / 160f
        return ProbeRegion(
            left = 24f * pxPerDp / widthPx,
            top = 120f * pxPerDp / heightPx,
            width = 192f * pxPerDp / widthPx,
            height = 192f * pxPerDp / heightPx,
        )
    }

    private data class ProbeRegion(val left: Float, val top: Float, val width: Float, val height: Float)

    private suspend fun openProbeSocket(devices: DesktopDeviceService, serial: String): ProbeSocketTrigger {
        val adb = devices.adbPath() ?: error("ADB not found")
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            adb,
            "-s", serial,
            "forward",
            "tcp:$port",
            "localabstract:andy-latency-probe",
        ).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Timed out forwarding latency-probe socket")
        assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
        val socket = Socket("127.0.0.1", port).also { it.tcpNoDelay = true }
        return ProbeSocketTrigger(adb, serial, port, socket)
    }

    private class ProbeSocketTrigger(
        private val adb: String,
        private val serial: String,
        private val port: Int,
        private val socket: Socket,
    ) : AutoCloseable {
        fun pulse() {
            socket.getOutputStream().write(1)
            socket.getOutputStream().flush()
        }

        override fun close() {
            runCatching { socket.close() }
            ProcessBuilder(adb, "-s", serial, "forward", "--remove", "tcp:$port")
                .start()
                .also { it.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}
