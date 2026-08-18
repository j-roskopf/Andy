package app.andy.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * When true, desktop [androidx.compose.ui.awt.SwingPanel] hosts (device mirror, DHU, editors)
 * should leave composition (not merely set child visibility) so in-window dialogs can paint.
 * Leaving an invisible Swing interop host still punches a Skia clear-hole and can keep a
 * white JPanel above the dialog.
 *
 * Chrome menus no longer use this: they expand in-layout under the toolbar / dock tab strip
 * so Live and Browser reflow instead of fighting z-order.
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

/**
 * Main-content scroll in progress (chat transcript, etc.). Live mirrors pause Metal presentation
 * while this is held so sibling Compose scrolling is not fighting the GPU presenter every frame.
 *
 * Refcounted: multiple scrollable surfaces can be busy at once.
 */
internal object ContentScrollBusyRegistry {
    private var busyCount by mutableStateOf(0)

    val anyBusy: Boolean get() = busyCount > 0

    fun begin() {
        busyCount++
    }

    fun end() {
        busyCount = (busyCount - 1).coerceAtLeast(0)
    }
}

/** Gap between trackpad/wheel ticks before treating scroll as finished. */
internal const val ContentScrollBusyReleaseDebounceMs = 120L

/**
 * Holds [ContentScrollBusyRegistry] while [listState] reports scroll in progress, or while
 * [wheelScrollTicks] emits (desktop wheel/trackpad often never sets [LazyListState.isScrollInProgress]).
 */
@Composable
internal fun ReportContentScrollBusy(
    listState: LazyListState,
    wheelScrollTicks: Flow<Unit>,
) {
    LaunchedEffect(listState, wheelScrollTicks) {
        var held = false
        fun acquire() {
            if (!held) {
                ContentScrollBusyRegistry.begin()
                held = true
            }
        }
        fun release() {
            if (held) {
                ContentScrollBusyRegistry.end()
                held = false
            }
        }
        try {
            coroutineScope {
                var releaseJob: Job? = null
                fun scheduleRelease() {
                    releaseJob?.cancel()
                    releaseJob = launch {
                        delay(ContentScrollBusyReleaseDebounceMs)
                        if (!listState.isScrollInProgress) release()
                    }
                }
                launch {
                    snapshotFlow { listState.isScrollInProgress }
                        .distinctUntilChanged()
                        .collect { inProgress ->
                            if (inProgress) {
                                releaseJob?.cancel()
                                acquire()
                            } else {
                                scheduleRelease()
                            }
                        }
                }
                launch {
                    wheelScrollTicks.collect {
                        releaseJob?.cancel()
                        acquire()
                        scheduleRelease()
                    }
                }
            }
        } finally {
            release()
        }
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
