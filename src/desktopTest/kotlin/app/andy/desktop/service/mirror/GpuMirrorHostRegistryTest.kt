package app.andy.desktop.service.mirror

import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GpuMirrorHostRegistryTest {
    @AfterTest
    fun tearDown() {
        GpuMirrorSessions.clear()
    }

    @Test
    fun registerReplaceClosesPreviousPresenter() {
        if (!GpuMirrorJni.isAvailable()) return

        SwingUtilities.invokeAndWait {
            val host = realizedCanvas("registry-replace")
            val pipeline = GpuMirrorSessions.createAndBind("registry-replace")!!
            try {
                val first = pipeline.createPresenter()!!
                assertTrue(first.attach(host, fillHost = false))
                assertSame(first, GpuMirrorHostRegistry.presenterFor(host))

                val second = pipeline.createPresenter()!!
                assertTrue(second.attach(host, fillHost = true))
                assertSame(second, GpuMirrorHostRegistry.presenterFor(host))
                assertEquals(1, GpuMirrorHostRegistry.presentersForDecoder(pipeline.decoderId).size)
            } finally {
                GpuMirrorSessions.release("registry-replace")
                disposeCanvas(host)
            }
        }
    }

    @Test
    fun hostInWindowFindsPresenterInsidePopOutFrame() {
        if (!GpuMirrorJni.isAvailable()) return

        SwingUtilities.invokeAndWait {
            val live = realizedCanvas("registry-live")
            val popOut = realizedCanvas("registry-pop-out")
            val pipeline = GpuMirrorSessions.createAndBind("registry-window")!!
            try {
                val livePresenter = pipeline.createPresenter()!!
                val popPresenter = pipeline.createPresenter()!!
                assertTrue(livePresenter.attach(live, fillHost = false))
                assertTrue(popPresenter.attach(popOut, fillHost = true))

                val popWindow = SwingUtilities.getWindowAncestor(popOut)!!
                assertSame(popOut, GpuMirrorHostRegistry.hostInWindow(popWindow))
                assertNull(GpuMirrorHostRegistry.hostInWindow(SwingUtilities.getWindowAncestor(live)!!)?.takeIf { it === popOut })
                assertNotNull(GpuMirrorHostRegistry.hostInWindow(SwingUtilities.getWindowAncestor(live)!!))
            } finally {
                GpuMirrorSessions.release("registry-window")
                disposeCanvas(popOut)
                disposeCanvas(live)
            }
        }
    }

    private fun realizedCanvas(title: String): Canvas {
        val canvas = Canvas()
        val frame = JFrame(title)
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(canvas, BorderLayout.CENTER)
        frame.setSize(128, 220)
        frame.isVisible = true
        return canvas
    }

    private fun disposeCanvas(canvas: Canvas) {
        SwingUtilities.getWindowAncestor(canvas)?.dispose()
    }
}
