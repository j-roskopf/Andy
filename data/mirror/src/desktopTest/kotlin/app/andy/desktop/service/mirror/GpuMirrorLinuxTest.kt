package app.andy.desktop.service.mirror

import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GpuMirrorLinuxTest {
    @AfterTest
    fun tearDown() {
        GpuMirrorSessions.clear()
    }

    @Test
    fun linuxAmd64BridgeLoadsFromPackagedDesktopResources() {
        if (!isLinuxAmd64()) return
        assertTrue(GpuMirrorJni.isAvailable())
    }

    @Test
    fun linuxBridgePresentsSolidBgraOnRealizedSwingHost() {
        if (!isLinuxAmd64() || !GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        lateinit var presenter: GpuMirrorPresenter
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("gpu-linux-bgra")
            pipeline = GpuMirrorSessions.createAndBind("gpu-linux-bgra")!!
            presenter = pipeline.createPresenter()!!
            assertTrue(presenter.attach(host, fillHost = true))
            presenter.setContentSize(64, 128)
            assertTrue(pipeline.presentSolidBgra(64, 128, blue = 40, green = 90, red = 220))
            presenter.updateOverlay(
                gridEnabled = true,
                gridStepX = .1f,
                gridStepY = .05f,
                gridR = 1f,
                gridG = 1f,
                gridB = 1f,
                gridA = .3f,
                rulerEnabled = true,
                rulerX = .5f,
                rulerY = .5f,
                rulerR = 1f,
                rulerG = .4f,
                rulerB = .2f,
                rulerA = 1f,
                sourceWidth = 64f,
                sourceHeight = 128f,
                pickerEnabled = false,
                highlightLeft = 0f,
                highlightTop = 0f,
                highlightRight = 0f,
                highlightBottom = 0f,
            )
        }
        SwingUtilities.invokeAndWait { }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (pipeline.framesPresented() <= 0L && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }

        assertTrue(pipeline.hasDecodedFrame(), "Solid BGRA should be stored on the hub decoder")
        assertTrue(pipeline.framesPresented() > 0, "Expected the Vulkan overlay to present a frame")
        val frame = pipeline.copyLatestFrameArgb()
        assertNotNull(frame)
        assertEquals(64, frame.width)
        assertEquals(128, frame.height)
        val pixel = frame.argb[0]
        assertEquals(220, pixel ushr 16 and 0xff)
        assertEquals(90, pixel ushr 8 and 0xff)
        assertEquals(40, pixel and 0xff)

        SwingUtilities.invokeAndWait {
            GpuMirrorSessions.release("gpu-linux-bgra")
            SwingUtilities.getWindowAncestor(host)?.dispose()
        }
    }

    private fun realizedCanvas(title: String): Canvas {
        val canvas = Canvas()
        val frame = JFrame(title)
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(canvas, BorderLayout.CENTER)
        frame.setSize(180, 320)
        frame.isVisible = true
        return canvas
    }
}
