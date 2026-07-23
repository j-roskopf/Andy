package app.andy.desktop.service.ios

import app.andy.desktop.service.createDesktopServices
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorVideoConfig
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import app.andy.MirrorVideoSurface
import app.andy.service.MirrorFrame
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class IosSimMirrorDeviceSmokeTest {
    @Test
    fun simulatorPresentsFramesAndAcceptsInput() = runBlocking {
        if (System.getenv("ANDY_IOS_SIM_SMOKE") != "1") return@runBlocking
        if (!NativeIosSimJni.isAvailable()) return@runBlocking
        if (!NativeMirrorJni.isEmbeddedPresentationSupported()) return@runBlocking

        val udid = System.getenv("ANDY_IOS_SIM_UDID")?.takeIf { it.isNotBlank() }
            ?: return@runBlocking

        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frame = MirrorFrame(2, 2, IntArray(4)),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            frame = JFrame("Andy iOS sim smoke")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(332, 720)
            frame.isVisible = true
        }

        val hostDeadline = System.nanoTime() + 5_000_000_000L
        while (NativeMirrorHostRegistry.current() == null && System.nanoTime() < hostDeadline) {
            Thread.sleep(20)
        }
        assertTrue(NativeMirrorHostRegistry.current() != null, "Compose Live surface did not realize a native JAWT host")

        val services = createDesktopServices()
        val result = services.mirror.connect(
            udid,
            MirrorVideoConfig(rendererMode = MirrorRendererMode.Accelerated),
        )
        assertTrue(result.isSuccess, result.stderr.ifBlank { result.stdout })

        val presentedDeadline = System.nanoTime() + 15_000_000_000L
        while (NativeMirrorJni.framesPresented() <= 0 && System.nanoTime() < presentedDeadline) {
            delay(50)
        }
        assertTrue(NativeMirrorJni.framesPresented() > 0, "Expected simulator frames to be presented")
        assertTrue(NativeMirrorJni.isHardwareReady(), "Metal presenter was not ready")

        val session = services.mirror.session.value
        assertTrue((session?.width ?: 0) > 100, "Expected plausible simulator width")
        assertTrue((session?.height ?: 0) > 100, "Expected plausible simulator height")

        val beforeTransitions = NativeMirrorJni.latencyProbeTransitions()
        val points = NativeIosSimJni.contentSizePoints()
        services.mirror.sendInput(
            MirrorInput.Tap(points[0] / 2, points[1] / 2),
        )
        delay(500)
        assertTrue(
            NativeMirrorJni.latencyProbeTransitions() > beforeTransitions,
            "Expected input-to-present telemetry after tap",
        )

        services.mirror.disconnect(immediate = true)
        SwingUtilities.invokeLater { frame.dispose() }
    }
}
