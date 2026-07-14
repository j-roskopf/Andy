package app.andy.desktop.service.mirror

import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import app.andy.MirrorVideoSurface
import app.andy.service.MirrorFrame
import java.awt.BorderLayout
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf

/** Proves the production Compose -> SwingPanel -> inline Metal host chain. */
class NativeMirrorComposeHostTest {
    @Test
    fun composeLiveSurfaceRegistersARealizedJAWTHost() {
        if (!System.getProperty("os.name").contains("mac", ignoreCase = true)) return
        if (System.getProperty("os.arch").lowercase() !in setOf("aarch64", "arm64")) return
        assertTrue(NativeMirrorJni.isAvailable())

        lateinit var window: JFrame
        SwingUtilities.invokeAndWait {
            val compose = ComposePanel().apply {
                setContent {
                    MirrorVideoSurface(
                        frames = flowOf(MirrorFrame(2, 2, intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt()))),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            window = JFrame("Andy Compose JAWT mirror host")
            window.contentPane.layout = BorderLayout()
            window.contentPane.add(compose, BorderLayout.CENTER)
            window.setSize(160, 240)
            window.isVisible = true
        }

        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            var host = NativeMirrorHostRegistry.current()
            while (host == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                host = NativeMirrorHostRegistry.current()
            }
            host = assertNotNull(host, "Compose Live surface did not realize a native JAWT host")
            SwingUtilities.invokeAndWait {
                assertTrue(NativeMirrorJni.openMetalInlineOverlay(host))
                NativeMirrorJni.setPresentationContentSize(90, 200)
                NativeMirrorHostRegistry.unregister(host)
                NativeMirrorJni.removeMetalLayer(host)
                NativeMirrorJni.destroyPresentation()
            }
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
