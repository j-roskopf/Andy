package app.andy.desktop.service.mirror

import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression tests for the shared Metal inline overlay used by Live and pop-out mirrors.
 *
 * These run on macOS arm64 only because they exercise the packaged JNI bridge.
 */
class NativeMirrorPresentationLifecycleTest {
    @AfterTest
    fun tearDown() {
        NativeMirrorJni.destroyPresentation()
    }

    @Test
    fun openInlineOverlayClearsSuppressionAndStaysOpen() {
        if (!isMacArm64()) return

        SwingUtilities.invokeAndWait {
            val host = realizedCanvas("overlay-open")
            try {
                NativeMirrorHostRegistry.markHostsMetalPresentation(host, hostsMetal = true)
                NativeMirrorHostRegistry.register(host)
                assertTrue(NativeMirrorJni.openMetalInlineOverlay(host))
                assertTrue(NativeMirrorJni.isMetalInlineOverlayOpen())

                NativeMirrorJni.setInlineOverlayVisible(false)
                NativeMirrorJni.setInlineOverlayVisible(true)
                NativeMirrorJni.setPresentationContentSize(1080, 1920)
                NativeMirrorJni.updateMetalLayerGeometry(host)
                NativeMirrorJni.repaintLatestFrame()
            } finally {
                NativeMirrorHostRegistry.unregister(host)
                disposeCanvas(host)
            }
        }
    }

    @Test
    fun popOutHostRebindsPresentationWithoutLiveHostStealingGeometry() {
        if (!isMacArm64()) return

        SwingUtilities.invokeAndWait {
            val live = realizedCanvas("live")
            val popOut = realizedCanvas("pop-out")
            try {
                listOf(live, popOut).forEach {
                    NativeMirrorHostRegistry.markHostsMetalPresentation(it, hostsMetal = true)
                    NativeMirrorHostRegistry.register(it)
                }
                assertTrue(NativeMirrorJni.openMetalInlineOverlay(live))
                NativeMirrorJni.setPresentationContentSize(1080, 1920)

                assertSame(popOut, NativeMirrorHostRegistry.current())
                NativeMirrorJni.updateMetalLayerGeometry(popOut)
                NativeMirrorJni.repaintLatestFrame()

                // Live resize/move callbacks must not reposition the shared overlay once pop-out is active.
                NativeMirrorJni.updateMetalLayerGeometry(live)
                NativeMirrorJni.updateMetalLayerGeometry(popOut)
                NativeMirrorJni.repaintLatestFrame()
            } finally {
                NativeMirrorHostRegistry.unregister(popOut)
                NativeMirrorHostRegistry.unregister(live)
                NativeMirrorJni.removeMetalLayer(popOut)
                disposeCanvas(popOut)
                disposeCanvas(live)
            }
        }
    }

    @Test
    fun closingPopOutRebindsPresentationToRemainingLiveHost() {
        if (!isMacArm64()) return

        SwingUtilities.invokeAndWait {
            val live = realizedCanvas("live-remaining")
            val popOut = realizedCanvas("pop-out-closing")
            try {
                listOf(live, popOut).forEach {
                    NativeMirrorHostRegistry.markHostsMetalPresentation(it, hostsMetal = true)
                    NativeMirrorHostRegistry.register(it)
                }
                assertTrue(NativeMirrorJni.openMetalInlineOverlay(live))
                NativeMirrorJni.setPresentationContentSize(720, 1280)

                NativeMirrorHostRegistry.unregister(popOut)
                NativeMirrorJni.removeMetalLayer(popOut)
                disposeCanvas(popOut)

                assertSame(live, NativeMirrorHostRegistry.current())
                NativeMirrorJni.updateMetalLayerGeometry(live)
                NativeMirrorJni.repaintLatestFrame()
            } finally {
                NativeMirrorHostRegistry.unregister(live)
                disposeCanvas(live)
            }
        }
    }

    private fun isMacArm64(): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return false
        return System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
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
