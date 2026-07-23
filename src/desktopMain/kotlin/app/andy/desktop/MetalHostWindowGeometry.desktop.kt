package app.andy.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.WindowScope
import app.andy.desktop.service.mirror.NativeMirrorHostRegistry
import app.andy.desktop.service.mirror.NativeMirrorJni
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/** Refreshes the shared Metal overlay when a Compose window moves or resizes. */
@Composable
fun WindowScope.MetalHostWindowGeometryEffect() {
    DisposableEffect(window) {
        val refresh = Runnable {
            val host = NativeMirrorHostRegistry.current() ?: return@Runnable
            NativeMirrorJni.updateMetalLayerGeometry(host)
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
        onDispose { window.removeComponentListener(listener) }
    }
}
