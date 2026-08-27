package app.andy.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.WindowScope
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Claims the shared Metal presenter for this pop-out window and keeps geometry in sync while it
 * is open. Only the pop-out that owns the primary GPU mirror may promote; otherwise multiple
 * windows fight for one Metal surface and non-owners stay black.
 */
@Composable
fun WindowScope.PopOutMirrorPresentationEffect(ownsMetalPresentation: Boolean) {
    if (!ownsMetalPresentation) return
    LaunchedEffect(window) {
        while (isActive) {
            NativeMirrorHostRegistry.promoteWindow(window)
            if (NativeMirrorHostRegistry.hostInWindow(window) != null) break
            delay(16)
        }
    }
    DisposableEffect(window) {
        val refresh = Runnable {
            NativeMirrorHostRegistry.hostInWindow(window)?.let(NativeMirrorJni::updateMetalLayerGeometry)
        }
        val listener = object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) {
                refresh.run()
            }

            override fun componentResized(event: ComponentEvent) {
                refresh.run()
            }
        }
        window.addComponentListener(listener)
        onDispose {
            window.removeComponentListener(listener)
            NativeMirrorHostRegistry.relinquishWindow(window)
        }
    }
}
