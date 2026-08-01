package app.andy.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * When true, desktop [androidx.compose.ui.awt.SwingPanel] hosts (device mirror, DHU, editors)
 * should leave composition (not merely set child visibility) so chrome menus and dialogs can
 * paint. Leaving an invisible Swing interop host still punches a Skia clear-hole and can keep a
 * white JPanel above the menu.
 */
internal val LocalSuppressHeavyweightSurfaces = compositionLocalOf { false }

/**
 * Open in-window modal dialogs, counted app-wide.
 *
 * Compose renders [androidx.compose.material3.AlertDialog] into the main window's own popup
 * layer, which still paints *below* Swing/Metal interop surfaces — a dialog opened over a device
 * mirror is invisible. Dialogs register here for as long as they are
 * composed and the shell folds that into [LocalSuppressHeavyweightSurfaces], so the interop
 * hosts leave composition while the dialog is up.
 *
 * This is deliberately a shell-wide signal rather than a CompositionLocal: dialogs are declared
 * far below the provider they need to flip.
 */
internal object ModalDialogRegistry {
    private var openCount by mutableStateOf(0)

    val anyOpen: Boolean get() = openCount > 0

    fun open() {
        openCount++
    }

    fun close() {
        openCount = (openCount - 1).coerceAtLeast(0)
    }
}

/** Keeps heavyweight interop surfaces out of composition while the caller is composed. */
@Composable
internal fun SuppressHeavyweightSurfacesWhileOpen() {
    DisposableEffect(Unit) {
        ModalDialogRegistry.open()
        onDispose { ModalDialogRegistry.close() }
    }
}

/** True while the main Andy window is actively being resized (desktop only). */
internal val LocalWindowResizing = compositionLocalOf { false }
