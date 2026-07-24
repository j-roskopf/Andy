package app.andy.desktop.service.mirror

import app.andy.MirrorVideoSurface
import app.andy.awaitMirrorSurfaceReady
import app.andy.service.MirrorFrame
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import java.awt.BorderLayout
import java.awt.Robot
import java.awt.event.InputEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * End-to-end Compose host wiring for the GPU hub path used by Live / pop-out.
 * Catches regressions where the surface forgets nativePresentation / stream keys
 * and leaves a black Canvas with no Metal presenter.
 */
class GpuMirrorComposeSurfaceTest {
    @AfterTest
    fun tearDown() {
        GpuMirrorSessions.clear()
    }

    @Test
    fun flowSurfaceAttachesPresenterAndStaysNonBlackAfterClick() = runBlocking {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return@runBlocking

        val streamKey = "compose-flow-device"
        val pipeline = GpuMirrorSessions.createAndBind(streamKey)!!
        val frames = MutableStateFlow(MirrorFrame(1080, 1920, IntArray(0), frameNumber = 1))

        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frames = frames,
                        resetKey = streamKey,
                        modifier = Modifier.fillMaxSize(),
                        nativePresentation = true,
                        gpuMirrorStreamKey = streamKey,
                    )
                }
            }
            frame = JFrame("GPU Compose flow surface")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(240, 420)
            frame.isVisible = true
        }

        assertTrue(awaitMirrorSurfaceReady(5_000), "awaitMirrorSurfaceReady must see GpuMirrorHostRegistry")
        val host = assertNotNull(awaitGpuMirrorHost(), "Compose flow surface did not register a GPU host")

        SwingUtilities.invokeAndWait {
            assertTrue(pipeline.presentSolidBgra(96, 192, blue = 50, green = 120, red = 240))
        }
        Thread.sleep(120)
        assertTrue(mirrorHostContainsNonBlackPixels(host), "Live Compose surface stayed black after present")

        val loc = host.locationOnScreen
        Robot().apply {
            autoDelay = 20
            mouseMove(loc.x + host.width / 2, loc.y + host.height / 2)
            mousePress(InputEvent.BUTTON1_DOWN_MASK)
            mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        Thread.sleep(150)
        SwingUtilities.invokeAndWait {
            assertTrue(pipeline.presentSolidBgra(96, 192, blue = 50, green = 120, red = 240))
        }
        Thread.sleep(100)

        assertTrue(
            mirrorHostContainsNonBlackPixels(host),
            "Compose Live surface went black after click — Metal lost z-order to the Canvas",
        )

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release(streamKey)
            frame.dispose()
        }
    }

    @Test
    fun singleFrameSurfaceHonorsNativePresentationFlags() = runBlocking {
        if (!isMacArm64() || !GpuMirrorJni.isAvailable()) return@runBlocking

        val streamKey = "compose-single-frame"
        val pipeline = GpuMirrorSessions.createAndBind(streamKey)!!

        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frame = MirrorFrame(720, 1280, IntArray(0), frameNumber = 2),
                        modifier = Modifier.fillMaxSize(),
                        nativePresentation = true,
                        nativePresentationFillHost = true,
                        gpuMirrorStreamKey = streamKey,
                    )
                }
            }
            frame = JFrame("GPU Compose single-frame surface")
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(compose, BorderLayout.CENTER)
            frame.setSize(240, 420)
            frame.isVisible = true
        }

        assertTrue(awaitMirrorSurfaceReady(5_000))
        val host = assertNotNull(
            awaitGpuMirrorHost(),
            "Single-frame MirrorVideoSurface ignored nativePresentation/gpuMirrorStreamKey",
        )

        SwingUtilities.invokeAndWait {
            assertTrue(pipeline.presentSolidBgra(64, 128, blue = 180, green = 60, red = 40))
        }
        Thread.sleep(120)
        assertTrue(mirrorHostContainsNonBlackPixels(host), "Single-frame GPU surface stayed black")

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release(streamKey)
            frame.dispose()
        }
    }
}
