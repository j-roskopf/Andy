package app.andy.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** Main-content scroll in progress; Live mirrors pause Metal presentation while busy. */
object ContentScrollBusyRegistry {
    private var busyCount by mutableStateOf(0)

    val anyBusy: Boolean get() = busyCount > 0

    fun begin() {
        busyCount++
    }

    fun end() {
        busyCount = (busyCount - 1).coerceAtLeast(0)
    }
}

const val ContentScrollBusyReleaseDebounceMs = 120L

@Composable
fun ReportContentScrollBusy(
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
