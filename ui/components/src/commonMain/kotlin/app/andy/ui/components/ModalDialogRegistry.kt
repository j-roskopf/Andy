package app.andy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.LocalSuppressHeavyweightSurfaces

/**
 * Open in-window modal dialogs, counted app-wide. The shell folds [anyOpen] into
 * [LocalSuppressHeavyweightSurfaces] so Swing/Metal interop hosts leave composition while
 * dialogs are up.
 */
object ModalDialogRegistry {
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
fun SuppressHeavyweightSurfacesWhileOpen() {
    DisposableEffect(Unit) {
        ModalDialogRegistry.open()
        onDispose { ModalDialogRegistry.close() }
    }
}
