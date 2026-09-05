package app.andy.desktop.service.remote

import app.andy.service.LocalServerProcess
import app.andy.service.LocalServerService
import app.andy.service.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Swaps [LocalServerService] between the local host scan and an SSH-backed remote
 * scan when a desktop remote session connects / disconnects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwappableLocalServerService(
    initial: LocalServerService,
    scope: CoroutineScope,
) : LocalServerService {
    private val active = AtomicReference(initial)
    private val activeFlow = MutableStateFlow(initial)
    private val watchCount = AtomicInteger(0)

    override val servers: StateFlow<List<LocalServerProcess>> =
        activeFlow
            .flatMapLatest { it.servers }
            .stateIn(scope, SharingStarted.Eagerly, initial.servers.value)

    fun switchTo(next: LocalServerService) {
        val prev = active.get()
        if (prev === next) return
        val watching = watchCount.get() > 0
        if (watching) prev.stopWatching()
        active.set(next)
        activeFlow.value = next
        if (watching) next.startWatching()
    }

    private fun svc(): LocalServerService = active.get()

    override fun startWatching() {
        if (watchCount.getAndIncrement() > 0) return
        svc().startWatching()
    }

    override fun stopWatching() {
        if (watchCount.updateAndGet { (it - 1).coerceAtLeast(0) } > 0) return
        svc().stopWatching()
    }

    override suspend fun refresh() = svc().refresh()

    override suspend fun stop(pid: Int, port: Int): CommandResult = svc().stop(pid, port)
}
