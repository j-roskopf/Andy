package app.andy.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.andy.desktop.service.mirror.NativeMirrorJni
import javax.swing.SwingUtilities

@Composable
internal actual fun MirrorPresentationVisibilityEffect(visible: Boolean, enabled: Boolean) {
    LaunchedEffect(visible, enabled) {
        if (!enabled || !NativeMirrorJni.isMetalInlineOverlayOpen()) return@LaunchedEffect
        SwingUtilities.invokeLater {
            NativeMirrorJni.setInlineOverlayVisible(visible)
        }
    }
}
