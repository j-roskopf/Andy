package app.andy.service

import app.andy.model.Automation
import app.andy.model.AutomationDraft
import kotlinx.coroutines.flow.StateFlow

interface AutomationService {
    val automations: StateFlow<List<Automation>>

    suspend fun create(draft: AutomationDraft, arm: Boolean): Automation
    suspend fun update(id: String, draft: AutomationDraft): Automation
    suspend fun pause(id: String, reason: String? = null)
    suspend fun resume(id: String)
    suspend fun delete(id: String)
    suspend fun runNow(id: String): Automation
}

object UnavailableAutomationService : AutomationService {
    override val automations = kotlinx.coroutines.flow.MutableStateFlow(emptyList<Automation>())
    override suspend fun create(draft: AutomationDraft, arm: Boolean): Automation =
        error("Automations require Andy Desktop with andyd running.")
    override suspend fun update(id: String, draft: AutomationDraft): Automation =
        error("Automations require Andy Desktop with andyd running.")
    override suspend fun pause(id: String, reason: String?) = Unit
    override suspend fun resume(id: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun runNow(id: String): Automation =
        error("Automations require Andy Desktop with andyd running.")
}
