package app.andy.desktop.service.remote

import app.andy.model.Automation
import app.andy.model.AutomationDraft
import app.andy.service.AutomationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Stable [AutomationService] facade for local ↔ remote andyd swaps. */
class SwappableAutomationService(
    initial: AutomationService,
    private val scope: CoroutineScope,
) : AutomationService {
    private val active = AtomicReference(initial)
    private val _automations = MutableStateFlow(initial.automations.value)
    private var mirrorJob: Job? = null

    init {
        restartMirrors(initial)
    }

    fun switchTo(next: AutomationService) {
        active.set(next)
        restartMirrors(next)
    }

    private fun restartMirrors(backend: AutomationService) {
        mirrorJob?.cancel()
        _automations.value = backend.automations.value
        mirrorJob = scope.launch {
            backend.automations.collectLatest { _automations.value = it }
        }
    }

    private fun svc(): AutomationService = active.get()

    override val automations: StateFlow<List<Automation>> = _automations.asStateFlow()
    override suspend fun create(draft: AutomationDraft, arm: Boolean): Automation = svc().create(draft, arm)
    override suspend fun update(id: String, draft: AutomationDraft): Automation = svc().update(id, draft)
    override suspend fun pause(id: String, reason: String?) = svc().pause(id, reason)
    override suspend fun resume(id: String) = svc().resume(id)
    override suspend fun delete(id: String) = svc().delete(id)
    override suspend fun runNow(id: String): Automation = svc().runNow(id)
}
