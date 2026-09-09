package app.andy.desktop

import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Pop-out mirror windows must block presenter attach while they are being resized, exactly like the
 * main window. Watching only the main window let pop-out resize drags open an overlay from the EDT
 * mid-drag — a main-thread dispatch_sync against AWT's own main→EDT hop, which froze the whole app.
 * Geometry itself stays live so the mirror tracks the drag; only attach waits for the settle.
 */
class MirrorWindowResizeWatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: MirrorWindowResizeWatch? = null
    private var frame: JFrame? = null

    @AfterTest
    fun tearDown() {
        watch?.uninstall()
        SwingUtilities.invokeAndWait { frame?.dispose() }
        scope.cancel()
    }

    @Test
    fun resizingASecondaryWindowSuppressesPresenterAttach() {
        if (GraphicsEnvironment.isHeadless()) return

        val resizingStates = mutableListOf<Boolean>()
        val watch = MirrorWindowResizeWatch(
            settleMillis = 120,
            onResizingChanged = { synchronized(resizingStates) { resizingStates += it } },
        ).also { this.watch = it }
        watch.install(scope)

        // Stand-in for a pop-out mirror window: a window that is not the main Andy window.
        val popOut = showFrame("pop-out-resize")
        SwingUtilities.invokeAndWait { popOut.setSize(420, 760) }
        flushEdt()

        assertTrue(
            MirrorPresentationGuard.suppressingAttach,
            "Resizing a pop-out window must suppress presenter attach",
        )
        assertTrue(
            synchronized(resizingStates) { resizingStates.contains(true) },
            "Pop-out resize must report the window as resizing",
        )

        awaitSettled()
        assertFalse(
            MirrorPresentationGuard.suppressingAttach,
            "Attach must resume once the resize settles",
        )
    }

    @Test
    fun uninstallReleasesTheGuardEvenMidResize() {
        if (GraphicsEnvironment.isHeadless()) return

        val watch = MirrorWindowResizeWatch(settleMillis = 5_000).also { this.watch = it }
        watch.install(scope)

        val popOut = showFrame("pop-out-uninstall")
        SwingUtilities.invokeAndWait { popOut.setSize(300, 640) }
        flushEdt()
        assertTrue(MirrorPresentationGuard.suppressingAttach)

        watch.uninstall()
        assertFalse(
            MirrorPresentationGuard.suppressingAttach,
            "Tearing the watch down must not strand mirrors with attach suppressed",
        )
    }

    @Test
    fun heavyweightPopupResizeDoesNotBlankTheMirror() {
        if (GraphicsEnvironment.isHeadless()) return

        // Realize the owner first so only the popup itself resizes once the watch is installed.
        val owner = showFrame("popup-owner")
        flushEdt()
        val watch = MirrorWindowResizeWatch(settleMillis = 120).also { this.watch = it }
        watch.install(scope)

        // Stand-in for a heavyweight Swing popup (menu / tooltip): a Window that is not a Frame.
        val popup = JWindow(owner)
        try {
            SwingUtilities.invokeAndWait {
                popup.setSize(120, 200)
                popup.isVisible = true
                popup.setSize(140, 240)
            }
            flushEdt()
            awaitSettled()
            assertFalse(
                MirrorPresentationGuard.suppressingAttach,
                "Popup windows opening must not suspend presenter attach",
            )
        } finally {
            SwingUtilities.invokeAndWait { popup.dispose() }
        }
    }

    private fun showFrame(title: String): JFrame {
        lateinit var created: JFrame
        SwingUtilities.invokeAndWait {
            created = JFrame(title).apply {
                setSize(240, 520)
                isVisible = true
            }
        }
        frame = created
        return created
    }

    private fun flushEdt() {
        SwingUtilities.invokeAndWait { }
    }

    private fun awaitSettled(timeoutMillis: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (MirrorPresentationGuard.suppressingAttach && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        flushEdt()
    }
}
