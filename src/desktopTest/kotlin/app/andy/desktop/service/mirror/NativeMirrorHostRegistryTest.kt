package app.andy.desktop.service.mirror

import java.awt.BorderLayout
import java.awt.Canvas
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class NativeMirrorHostRegistryTest {
    @AfterTest
    fun tearDown() {
        NativeMirrorJni.destroyPresentation()
    }

    @Test
    fun currentPrefersLastDisplayableHost() {
        SwingUtilities.invokeAndWait {
            val first = realizedCanvas("first")
            val second = realizedCanvas("second")
            try {
                NativeMirrorHostRegistry.register(first)
                NativeMirrorHostRegistry.register(second)
                assertSame(second, NativeMirrorHostRegistry.current())
            } finally {
                disposeCanvas(first)
                disposeCanvas(second)
            }
        }
    }

    @Test
    fun otherDisplayableSkipsExcludedHost() {
        SwingUtilities.invokeAndWait {
            val live = realizedCanvas("live")
            val popOut = realizedCanvas("pop-out")
            try {
                NativeMirrorHostRegistry.register(live)
                NativeMirrorHostRegistry.register(popOut)
                assertSame(popOut, NativeMirrorHostRegistry.current())
                assertSame(live, NativeMirrorHostRegistry.otherDisplayable(popOut))
            } finally {
                disposeCanvas(live)
                disposeCanvas(popOut)
            }
        }
    }

    @Test
    fun unregisterRemovesHostFromActiveSelection() {
        SwingUtilities.invokeAndWait {
            val live = realizedCanvas("live")
            val popOut = realizedCanvas("pop-out")
            try {
                NativeMirrorHostRegistry.register(live)
                NativeMirrorHostRegistry.register(popOut)
                NativeMirrorHostRegistry.unregister(popOut)
                assertSame(live, NativeMirrorHostRegistry.current())
                assertEquals(1, NativeMirrorHostRegistry.registeredHostsForTests().size)
            } finally {
                disposeCanvas(live)
                disposeCanvas(popOut)
            }
        }
    }

  private fun realizedCanvas(title: String): Canvas {
        val canvas = Canvas()
        val frame = JFrame(title)
        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(canvas, BorderLayout.CENTER)
        frame.setSize(96, 160)
        frame.isVisible = true
        assertNotNull(SwingUtilities.getWindowAncestor(canvas))
        return canvas
    }

    private fun disposeCanvas(canvas: Canvas) {
        NativeMirrorHostRegistry.unregister(canvas)
        SwingUtilities.getWindowAncestor(canvas)?.dispose()
    }
}
