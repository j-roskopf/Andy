package app.andy.service

import app.andy.model.OrchestrationPreferences

/** Load/save `~/.andy/orchestration-preferences.json` for Settings and orchestration skills. */
interface OrchestrationPreferencesService {
    fun load(): OrchestrationPreferences
    fun save(prefs: OrchestrationPreferences)
}

object UnavailableOrchestrationPreferencesService : OrchestrationPreferencesService {
    override fun load(): OrchestrationPreferences = OrchestrationPreferences.Defaults
    override fun save(prefs: OrchestrationPreferences) = Unit
}
