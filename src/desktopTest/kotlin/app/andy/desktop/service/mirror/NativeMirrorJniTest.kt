package app.andy.desktop.service.mirror

import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMirrorJniTest {
    @Test
    fun mapsBothSupportedMacArchitecturesToTheirPackagedBridge() {
        assertEquals(
            "andy-mirror/macos-arm64/andy-mirror-jni.dylib",
            NativeMirrorJni.resourcePath("Mac OS X", "aarch64"),
        )
        assertEquals(
            "andy-mirror/macos-x86_64/andy-mirror-jni.dylib",
            NativeMirrorJni.resourcePath("Darwin", "x86_64"),
        )
        assertNull(NativeMirrorJni.resourcePath("Windows 11", "amd64"))
    }

    @Test
    fun macosArm64BridgeLoadsFromPackagedDesktopResources() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        if (System.getProperty("os.arch").lowercase() !in setOf("aarch64", "arm64")) return

        assertTrue(NativeMirrorJni.isAvailable())
    }

    @Test
    fun macosArm64BridgeOpensInlineMetalOverlayOnRealizedSwingHost() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        if (System.getProperty("os.arch").lowercase() !in setOf("aarch64", "arm64")) return

        SwingUtilities.invokeAndWait {
            val host = Canvas()
            val frame = JFrame("Andy native mirror test")
            try {
                frame.contentPane.layout = BorderLayout()
                frame.contentPane.add(host, BorderLayout.CENTER)
                frame.setSize(64, 64)
                frame.isVisible = true
                assertTrue(NativeMirrorJni.openMetalInlineOverlay(host))
                NativeMirrorJni.setPresentationContentSize(40, 80)
                NativeMirrorJni.updateOverlay(
                    gridEnabled = true,
                    gridStep = .1f,
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
                    highlightLeft = .2f,
                    highlightTop = .2f,
                    highlightRight = .8f,
                    highlightBottom = .8f,
                )
                NativeMirrorHostRegistry.unregister(host)
                NativeMirrorJni.removeMetalLayer(host)
            } finally {
                NativeMirrorJni.destroyPresentation()
                frame.dispose()
            }
        }
    }

    @Test
    fun macosArm64BridgeCanReleaseAfterItsSwingHostIsDisposed() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        if (System.getProperty("os.arch").lowercase() !in setOf("aarch64", "arm64")) return

        lateinit var host: Canvas
        SwingUtilities.invokeAndWait {
            val frame = JFrame("Andy disposed native mirror host")
            host = Canvas()
            frame.contentPane.add(host, BorderLayout.CENTER)
            frame.setSize(64, 64)
            frame.isVisible = true
            assertTrue(NativeMirrorJni.openMetalInlineOverlay(host))
            frame.dispose()
        }
        assertTrue(!host.isDisplayable)
        NativeMirrorHostRegistry.unregister(host)
        NativeMirrorJni.removeMetalLayer(host)
        NativeMirrorJni.destroyPresentation()
    }
}
