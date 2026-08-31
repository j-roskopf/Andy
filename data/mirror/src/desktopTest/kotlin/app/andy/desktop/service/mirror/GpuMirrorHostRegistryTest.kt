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
import org.junit.Assume.assumeTrue

class GpuMirrorHostRegistryTest {
    @AfterTest
    fun tearDown() {
        GpuMirrorSessions.clear()
    }

    @Test
    fun registerReplaceClosesPreviousPresenter() {
        if (!GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        var firstAttached = false
        var secondAttached = false
        var first: GpuMirrorPresenter? = null
        var second: GpuMirrorPresenter? = null
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("registry-replace")
            pipeline = GpuMirrorSessions.createAndBind("registry-replace")!!
            first = pipeline.createPresenter()!!
            firstAttached = first!!.attach(host, fillHost = false)
            if (firstAttached) {
                second = pipeline.createPresenter()!!
                secondAttached = second!!.attach(host, fillHost = true)
            }
        }
        assumeTrue(
            "GPU presenter attach needs a working display/Vulkan stack",
            firstAttached && secondAttached,
        )
        try {
            assertSame(second, GpuMirrorHostRegistry.presenterFor(host))
            assertEquals(1, GpuMirrorHostRegistry.presentersForDecoder(pipeline.decoderId).size)
        } finally {
            SwingUtilities.invokeAndWait {
                GpuMirrorSessions.release("registry-replace")
                disposeCanvas(host)
            }
        }
    }

    @Test
    fun hostInWindowFindsPresenterInsidePopOutFrame() {
        if (!GpuMirrorJni.isAvailable()) return

        lateinit var live: Canvas
        lateinit var popOut: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        var attached = false
        SwingUtilities.invokeAndWait {
            live = realizedCanvas("registry-live")
            popOut = realizedCanvas("registry-pop-out")
            pipeline = GpuMirrorSessions.createAndBind("registry-window")!!
            val livePresenter = pipeline.createPresenter()!!
            val popPresenter = pipeline.createPresenter()!!
            attached = livePresenter.attach(live, fillHost = false) &&
                popPresenter.attach(popOut, fillHost = true)
        }
        assumeTrue(
            "GPU presenter attach needs a working display/Vulkan stack",
            attached,
        )
        try {
            val popWindow = SwingUtilities.getWindowAncestor(popOut)!!
            assertSame(popOut, GpuMirrorHostRegistry.hostInWindow(popWindow))
            assertNull(GpuMirrorHostRegistry.hostInWindow(SwingUtilities.getWindowAncestor(live)!!)?.takeIf { it === popOut })
            assertNotNull(GpuMirrorHostRegistry.hostInWindow(SwingUtilities.getWindowAncestor(live)!!))
        } finally {
            SwingUtilities.invokeAndWait {
                GpuMirrorSessions.release("registry-window")
                disposeCanvas(popOut)
                disposeCanvas(live)
            }
        }
    }

    @Test
    fun detachKeepsPresenterWarmForDecoderReuse() {
        if (!GpuMirrorJni.isAvailable()) return

        lateinit var host: Canvas
        lateinit var pipeline: GpuMirrorPipeline
        var attached = false
        var presenter: GpuMirrorPresenter? = null
        SwingUtilities.invokeAndWait {
            host = realizedCanvas("registry-warm-detach")
            pipeline = GpuMirrorSessions.createAndBind("registry-warm-detach")!!
            presenter = pipeline.createPresenter()!!
            attached = presenter!!.attach(host, fillHost = false)
        }
        assumeTrue(
            "GPU presenter attach needs a working display/Vulkan stack",
            attached,
        )
        try {
            SwingUtilities.invokeAndWait {
                presenter!!.detach()
            }
            assertNull(GpuMirrorHostRegistry.presenterFor(host))
            assertEquals(1, GpuMirrorHostRegistry.presentersForDecoder(pipeline.decoderId).size)
            assertSame(presenter, GpuMirrorHostRegistry.unattachedPresenterForDecoder(pipeline.decoderId))
        } finally {
            SwingUtilities.invokeAndWait {
                GpuMirrorSessions.release("registry-warm-detach")
                disposeCanvas(host)
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
