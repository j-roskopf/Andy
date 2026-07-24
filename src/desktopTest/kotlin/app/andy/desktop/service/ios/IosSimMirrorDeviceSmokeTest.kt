package app.andy.desktop.service.ios

import app.andy.desktop.parser.IosParsers
import app.andy.desktop.service.createDesktopServices
import app.andy.desktop.service.mirror.GpuMirrorHostRegistry
import app.andy.desktop.service.mirror.GpuMirrorJni
import app.andy.desktop.service.mirror.GpuMirrorSessions
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.desktop.service.mirror.awaitGpuMirrorHost
import app.andy.desktop.service.mirror.isMacArm64
import app.andy.desktop.service.mirror.mirrorHostContainsNonBlackPixels
import app.andy.model.DeviceConnectionState
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.IosTargetRegistry
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import app.andy.transfer.DeviceTransferCoordinator
import app.andy.ui.live.LiveScreen
import app.andy.ui.logcat.LogcatState
import app.andy.ui.shell.LocalSuppressHeavyweightSurfaces
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import app.andy.MirrorVideoSurface
import java.awt.BorderLayout
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import com.sun.net.httpserver.HttpServer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Device-backed iOS simulator Live regressions.
 *
 * Auto-runs when a simulator is already Booted (set `ANDY_IOS_SIM_SMOKE=0` to skip).
 * Optionally pin with `ANDY_IOS_SIM_UDID` / `ANDY_IOS_SIM_SMOKE=1`.
 */
class IosSimMirrorDeviceSmokeTest {
    @Test
    fun simulatorPresentsFramesAndAcceptsInput() = runBlocking {
        val udid = resolveIosSimSmokeUdid() ?: return@runBlocking
        if (!NativeIosSimJni.isAvailable()) return@runBlocking
        val useGpuHub = GpuMirrorJni.isAvailable()
        if (!useGpuHub && !NativeMirrorJni.isEmbeddedPresentationSupported()) return@runBlocking

        // Faithfully mirror the GUI: mirror routing keys off IosTargetRegistry, so an unregistered
        // UDID falls through to the Android engine and never runs the SimulatorKit path.
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = udid,
                    displayName = "iPhone sim smoke",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )

        lateinit var frame: JFrame
        val metadataFrames = MutableStateFlow(MirrorFrame(2, 2, IntArray(0), frameNumber = 1))
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frames = metadataFrames,
                        resetKey = udid,
                        modifier = Modifier.fillMaxSize(),
                        nativePresentation = true,
                        gpuMirrorStreamKey = udid.takeIf { useGpuHub },
                    )
                }
            }
            frame = JFrame("Andy iOS sim smoke")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(332, 720)
            frame.isVisible = true
        }

        val host = if (useGpuHub) {
            assertNotNull(GpuMirrorSessions.acquire(udid))
            assertNotNull(awaitGpuMirrorHost(5_000), "Compose Live surface did not realize a GPU hub host")
        } else {
            val hostDeadline = System.nanoTime() + 5_000_000_000L
            while (NativeMirrorHostRegistry.current() == null && System.nanoTime() < hostDeadline) {
                Thread.sleep(20)
            }
            assertTrue(NativeMirrorHostRegistry.current() != null, "Compose Live surface did not realize a native JAWT host")
            NativeMirrorHostRegistry.current()!!
        }

        val services = createDesktopServices()
        val connectStartedAt = System.nanoTime()
        val result = services.mirror.connect(
            udid,
            MirrorVideoConfig(rendererMode = MirrorRendererMode.Accelerated),
        )
        val connectMillis = (System.nanoTime() - connectStartedAt) / 1_000_000L
        assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })
        assertTrue(
            connectMillis < 12_000L,
            "iOS sim connect blocked for ${connectMillis}ms — HID warm-up must stay off the critical path",
        )

        val session = services.mirror.session.value
        assertTrue((session?.width ?: 0) > 100, "Expected plausible simulator width")
        assertTrue((session?.height ?: 0) > 100, "Expected plausible simulator height")
        assertTrue(
            (session?.stats?.framesPresented ?: 0L) > 0L,
            "Connect must seed framesPresented so Live does not keep a black loading overlay",
        )

        val presentedDeadline = System.nanoTime() + 15_000_000_000L
        fun presentedCount(): Long =
            if (useGpuHub) GpuMirrorSessions.get(udid)?.framesPresented() ?: 0L
            else NativeMirrorJni.framesPresented()
        while (presentedCount() <= 0 && System.nanoTime() < presentedDeadline) {
            delay(50)
        }
        assertTrue(presentedCount() > 0, "Expected simulator frames to be presented")
        // iOS delivers CVPixelBuffers straight from SimulatorKit — there is no VideoToolbox decode
        // session, so isHardwareReady() (which reflects the VT session) only applies to the legacy
        // H.264 path, never the GPU-hub iOS path.
        if (!useGpuHub) {
            assertTrue(NativeMirrorJni.isHardwareReady(), "Metal presenter was not ready")
        }
        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "iOS simulator Live host remained black after frames were presented",
        )

        val beforeTransitions = NativeMirrorJni.latencyProbeTransitions()
        val points = NativeIosSimJni.contentSizePoints()
        services.mirror.sendInput(
            MirrorInput.Tap(points[0] / 2, points[1] / 2),
        )
        delay(500)
        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "iOS simulator Live host went black after tap",
        )
        assertTrue(
            NativeMirrorJni.latencyProbeTransitions() > beforeTransitions || presentedCount() > 0,
            "Expected input-to-present telemetry after tap (or continued presentation)",
        )

        services.mirror.disconnect(immediate = true)
        if (useGpuHub) GpuMirrorSessions.release(udid)
        SwingUtilities.invokeLater { frame.dispose() }
    }

    /**
     * End-to-end GUI input: a real mouse swipe on the Live Canvas (via [Robot]) must travel
     * MirrorPanel.onInput → sender → engine → SimulatorKit HID and move the sim. Isolates whether
     * touch loss is in the UI click path vs the backend (backend is proven by the swipe diagnostic).
     */
    @Test
    fun guiSwipeOnCanvasReachesSimulator() = runBlocking {
        val udid = resolveIosSimSmokeUdid() ?: return@runBlocking
        if (!NativeIosSimJni.isAvailable() || !GpuMirrorJni.isAvailable()) return@runBlocking
        IosTargetRegistry.update(
            listOf(IosTarget(udid, "sim click", IosTargetKind.Simulator, IosTargetState.Booted)),
        )
        val services = createDesktopServices()
        val ioScope = CoroutineScope(Dispatchers.IO)
        val metadata = MutableStateFlow(MirrorFrame(1170, 2532, IntArray(0), frameNumber = 1))
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frames = metadata,
                        resetKey = udid,
                        modifier = Modifier.fillMaxSize(),
                        onInput = { input -> ioScope.launch { services.mirror.sendInput(input) } },
                        passThroughInput = true,
                        nativePresentation = true,
                        gpuMirrorStreamKey = udid,
                    )
                }
            }
            frame = JFrame("Andy iOS click smoke")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(360, 760)
            frame.isVisible = true
        }
        assertNotNull(GpuMirrorSessions.acquire(udid))
        val host = assertNotNull(awaitGpuMirrorHost(5_000), "Live surface did not realize a GPU host")
        val result = services.mirror.connect(udid, MirrorVideoConfig(rendererMode = MirrorRendererMode.Accelerated))
        assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })
        val pipeline = GpuMirrorSessions.get(udid)!!
        val deadline = System.nanoTime() + 15_000_000_000L
        while (pipeline.framesPresented() <= 0 && System.nanoTime() < deadline) delay(50)
        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "iOS simulator Live host remained black before GUI swipe",
        )
        // Feed the panel the real source size so mapPoint maps clicks into the device coordinate space.
        val active = services.mirror.session.value
        metadata.value = MirrorFrame(active?.width ?: 1170, active?.height ?: 2532, IntArray(0), frameNumber = 2)
        // Reset to the home screen so the swipe reliably opens Spotlight (a large screen change).
        services.mirror.sendInput(MirrorInput.Home)
        delay(1500)

        val before = pipeline.framesPresented()
        val loc = host.locationOnScreen
        Robot().apply {
            autoDelay = 15
            val cx = loc.x + host.width / 2
            val top = loc.y + host.height / 3
            mouseMove(cx, top)
            mousePress(InputEvent.BUTTON1_DOWN_MASK)
            for (i in 1..12) mouseMove(cx, top + i * (host.height / 3) / 12)
            mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        delay(2500)
        val after = pipeline.framesPresented()
        System.err.println(
            "IOS_GUI_CLICK_DIAG: hostSize=${host.width}x${host.height} framesBefore=$before" +
                " framesAfter=$after delta=${after - before}",
        )
        assertTrue(
            after > before,
            "A mouse swipe on the Live Canvas produced no simulator reaction (UI→HID click path broken)",
        )
        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "iOS simulator Live host went black after GUI swipe",
        )

        services.mirror.disconnect(immediate = true)
        GpuMirrorSessions.release(udid)
        ioScope.cancel()
        SwingUtilities.invokeLater { frame.dispose() }
    }

    /**
     * Reproduces the product startup path instead of pre-building a MirrorVideoSurface with fake
     * metadata. A booted, already-running simulator is selected directly in LiveScreen; the first
     * real mouse click after pixels appear must map through the Canvas and be acknowledged by HID.
     */
    @Test
    fun liveScreenFirstClickReachesAlreadyRunningSimulatorHid() = runBlocking {
        val udid = resolveIosSimSmokeUdid() ?: return@runBlocking
        if (!NativeIosSimJni.isAvailable() || !GpuMirrorJni.isAvailable()) return@runBlocking
        val target = IosTarget(udid, "sim Live startup", IosTargetKind.Simulator, IosTargetState.Booted)
        IosTargetRegistry.update(listOf(target))
        val tapFixture = launchSimulatorTapFixture(udid)

        val baseServices = createDesktopServices()
        val recordingMirror = InputResultMirror(baseServices.mirror)
        val services = baseServices.copy(mirror = recordingMirror)
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    LiveScreen(
                        services = services,
                        serial = udid,
                        device = null,
                        iosTarget = target,
                        devicePaneWidth = 680f,
                        controlsPaneHeight = 320f,
                        onStopEmulator = {},
                        stoppingEmulatorSerial = null,
                        stopStatus = "",
                        onDevicePaneWidthChange = {},
                        onControlsPaneHeightChange = {},
                        onBugSaved = {},
                        onRecordingSaved = {},
                        logcatState = LogcatState(),
                        onPopOutMirror = {},
                        selectedPackage = null,
                        onSelectedPackageChange = {},
                        transfer = DeviceTransferCoordinator(),
                    )
                }
            }
            frame = JFrame("Andy iOS Live startup input smoke")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(720, 820)
            frame.isVisible = true
        }

        try {
            val host = assertNotNull(
                awaitGpuMirrorHost(10_000),
                "LiveScreen did not realize its GPU host without pre-seeded metadata",
            )
            val sessionDeadline = System.nanoTime() + 15_000_000_000L
            while (
                ((services.mirror.session.value?.width ?: 0) <= 100 ||
                    (services.mirror.session.value?.height ?: 0) <= 100 ||
                    !mirrorHostContainsNonBlackPixels(host)) &&
                System.nanoTime() < sessionDeadline
            ) {
                delay(25)
            }
            assertTrue(
                mirrorHostContainsNonBlackPixels(host),
                "LiveScreen never showed simulator pixels before the first-click assertion",
            )

            val beforeTap = captureSimulatorScreenshot(udid)
            val loc = host.locationOnScreen
            Robot().apply {
                autoDelay = 10
                val x = loc.x + host.width / 2
                val y = loc.y + host.height / 2
                mouseMove(x, y)
                mousePress(InputEvent.BUTTON1_DOWN_MASK)
                mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }

            var sawDown = false
            var sawUp = false
            withTimeout(5_000) {
                while (!sawUp) {
                    val (input, result) = recordingMirror.inputResults.receive()
                    val touch = input as? app.andy.service.MirrorInput.Touch ?: continue
                    when (touch.action) {
                        app.andy.service.MirrorTouchAction.Down -> {
                            assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })
                            sawDown = true
                        }
                        app.andy.service.MirrorTouchAction.Up -> {
                            assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })
                            sawUp = true
                        }
                        app.andy.service.MirrorTouchAction.Move -> Unit
                    }
                }
            }
            assertTrue(sawDown && sawUp, "Live Canvas did not emit a complete touch gesture")
            delay(800)
            val afterTap = captureSimulatorScreenshot(udid)
            assertTrue(
                simulatorScreenshotChanged(beforeTap, afterTap),
                "Simulator HID acknowledged the Live tap, but the visible simulator UI did not change",
            )
        } finally {
            services.mirror.disconnect(immediate = true)
            SwingUtilities.invokeAndWait { frame.dispose() }
            tapFixture.close()
        }
    }

    @Test
    fun liveScreenStaysNonBlackAcrossIosAndroidIosSwitch() = runBlocking {
        val udid = resolveIosSimSmokeUdid() ?: return@runBlocking
        if (!NativeIosSimJni.isAvailable() || !GpuMirrorJni.isAvailable()) return@runBlocking
        val services = createDesktopServices()
        val android = services.devices.listDevices()
            .firstOrNull { it.state == DeviceConnectionState.Online }
            ?: return@runBlocking
        val ios = IosTarget(udid, "sim switch return", IosTargetKind.Simulator, IosTargetState.Booted)
        IosTargetRegistry.update(listOf(ios))
        val selectedSerial = androidx.compose.runtime.mutableStateOf(ios.udid)
        val selectedIos = androidx.compose.runtime.mutableStateOf<IosTarget?>(ios)
        val menuExpanded = androidx.compose.runtime.mutableStateOf(false)
        lateinit var frame: JFrame

        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    CompositionLocalProvider(LocalSuppressHeavyweightSurfaces provides menuExpanded.value) {
                    LiveScreen(
                        services = services,
                        serial = selectedSerial.value,
                        device = android.takeIf { selectedIos.value == null },
                        iosTarget = selectedIos.value,
                        devicePaneWidth = 680f,
                        controlsPaneHeight = 320f,
                        onStopEmulator = {},
                        stoppingEmulatorSerial = null,
                        stopStatus = "",
                        onDevicePaneWidthChange = {},
                        onControlsPaneHeightChange = {},
                        onBugSaved = {},
                        onRecordingSaved = {},
                        logcatState = LogcatState(),
                        onPopOutMirror = {},
                        selectedPackage = null,
                        onSelectedPackageChange = {},
                        transfer = DeviceTransferCoordinator(),
                    )
                    }
                }
            }
            frame = JFrame("Andy iOS Android iOS switch smoke")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(720, 820)
            frame.isVisible = true
        }

        suspend fun awaitNonBlackHost(serial: String, timeoutMillis: Long): java.awt.Canvas {
            val started = System.nanoTime()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                val pipeline = GpuMirrorSessions.get(serial)
                val host = pipeline?.let { active ->
                    GpuMirrorHostRegistry.registeredHostsForTests().firstOrNull { candidate ->
                        SwingUtilities.getWindowAncestor(candidate) == frame &&
                            GpuMirrorHostRegistry.presenterFor(candidate)?.decoderId == active.decoderId
                    }
                }
                if (
                    services.mirror.session.value?.serial == serial &&
                    host != null &&
                    mirrorHostContainsNonBlackPixels(host)
                ) {
                    println("LIVE_SWITCH_NON_BLACK serial=$serial elapsedMs=${(System.nanoTime() - started) / 1_000_000L}")
                    return host
                }
                delay(50)
            }
            error("Live host for $serial did not become non-black")
        }

        suspend fun openDeviceMenuAndAwaitSurfaceRemoval() {
            SwingUtilities.invokeAndWait { menuExpanded.value = true }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                val hasLiveHost = GpuMirrorHostRegistry.registeredHostsForTests().any {
                    SwingUtilities.getWindowAncestor(it) == frame
                }
                if (!hasLiveHost) return
                delay(20)
            }
            error("Opening the device menu did not remove the heavyweight Live host")
        }

        try {
            awaitNonBlackHost(ios.udid, 20_000)

            repeat(3) { cycle ->
                openDeviceMenuAndAwaitSurfaceRemoval()
                SwingUtilities.invokeAndWait {
                    selectedIos.value = null
                    selectedSerial.value = android.serial
                    menuExpanded.value = false
                }
                awaitNonBlackHost(android.serial, 35_000)

                openDeviceMenuAndAwaitSurfaceRemoval()
                SwingUtilities.invokeAndWait {
                    selectedIos.value = ios
                    selectedSerial.value = ios.udid
                    menuExpanded.value = false
                }
                val returnedIosHost = awaitNonBlackHost(ios.udid, 5_000)
                delay(1_000)
                assertTrue(
                    services.mirror.session.value?.serial == ios.udid &&
                        mirrorHostContainsNonBlackPixels(returnedIosHost),
                    "Cycle ${cycle + 1}: returned iOS session disconnected or became black",
                )
            }
        } finally {
            services.mirror.disconnect(immediate = true)
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}

private class InputResultMirror(
    private val delegate: MirrorEngine,
) : MirrorEngine by delegate {
    val inputResults = Channel<Pair<MirrorInput, CommandResult>>(Channel.UNLIMITED)

    override suspend fun sendInput(input: MirrorInput): CommandResult =
        delegate.sendInput(input).also { inputResults.trySend(input to it) }
}

private fun launchSimulatorTapFixture(udid: String): AutoCloseable {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val html = """
            <!doctype html>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <body onclick="document.body.style.background='#ef312f'"
                  style="margin:0;background:#1467d2;width:100vw;height:100vh"></body>
        """.trimIndent().encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html")
        exchange.sendResponseHeaders(200, html.size.toLong())
        exchange.responseBody.use { it.write(html) }
    }
    server.start()
    val url = "http://127.0.0.1:${server.address.port}/"
    val process = ProcessBuilder("xcrun", "simctl", "openurl", udid, url)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) {
        "Could not launch simulator tap fixture: $output"
    }
    Thread.sleep(1_000)
    return AutoCloseable { server.stop(0) }
}

private fun captureSimulatorScreenshot(udid: String): File {
    val output = File.createTempFile("andy-ios-input-", ".png").apply { delete() }
    val process = ProcessBuilder("xcrun", "simctl", "io", udid, "screenshot", output.absolutePath)
        .redirectErrorStream(true)
        .start()
    val diagnostic = process.inputStream.bufferedReader().readText()
    check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isFile) {
        "Could not capture simulator screenshot: $diagnostic"
    }
    output.deleteOnExit()
    return output
}

private fun simulatorScreenshotChanged(beforeFile: File, afterFile: File): Boolean {
    val before = ImageIO.read(beforeFile) ?: return false
    val after = ImageIO.read(afterFile) ?: return false
    if (before.width != after.width || before.height != after.height) return true
    var changed = 0
    var sampled = 0
    for (y in 0 until before.height step 4) {
        for (x in 0 until before.width step 4) {
            val a = before.getRGB(x, y)
            val b = after.getRGB(x, y)
            val delta =
                kotlin.math.abs((a shr 16 and 0xff) - (b shr 16 and 0xff)) +
                    kotlin.math.abs((a shr 8 and 0xff) - (b shr 8 and 0xff)) +
                    kotlin.math.abs((a and 0xff) - (b and 0xff))
            if (delta >= 45) changed++
            sampled++
        }
    }
    return changed.toDouble() / sampled.coerceAtLeast(1) >= 0.003
}

internal fun iosSimSmokeEnabled(): Boolean =
    when (System.getenv("ANDY_IOS_SIM_SMOKE")?.lowercase()) {
        "0", "false", "no" -> false
        "1", "true", "yes" -> true
        else -> isMacArm64() && firstBootedSimulatorUdid() != null
    }

internal fun resolveIosSimSmokeUdid(): String? {
    if (!iosSimSmokeEnabled()) return null
    return System.getenv("ANDY_IOS_SIM_UDID")?.takeIf { it.isNotBlank() }
        ?: firstBootedSimulatorUdid()
}

private fun firstBootedSimulatorUdid(): String? {
    val process = runCatching {
        ProcessBuilder("xcrun", "simctl", "list", "devices", "-j")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrNull() ?: return null
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly()
        return null
    }
    return IosParsers.parseSimctlDevices(output)
        .firstOrNull { it.kind == IosTargetKind.Simulator && it.state == IosTargetState.Booted }
        ?.udid
}
