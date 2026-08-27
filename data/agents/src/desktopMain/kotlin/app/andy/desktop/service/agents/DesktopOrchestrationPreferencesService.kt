package app.andy.desktop.service.agents

import app.andy.model.OrchestrationPreferences
import app.andy.service.OrchestrationPreferencesService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads/writes `~/.andy/orchestration-preferences.json` — the same file orchestration
 * skills consult for worker/verifier (and other role) provider defaults.
 */
class DesktopOrchestrationPreferencesService(
    private val file: File = File(System.getProperty("user.home"), ".andy/orchestration-preferences.json"),
) : OrchestrationPreferencesService {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    override fun load(): OrchestrationPreferences {
        if (!file.isFile) return OrchestrationPreferences.Defaults
        val text = runCatching { file.readText() }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return OrchestrationPreferences.Defaults
        return runCatching { json.decodeFromString<OrchestrationPreferences>(text).normalized() }
            .getOrElse { OrchestrationPreferences.Defaults }
    }

    override fun save(prefs: OrchestrationPreferences) {
        val normalized = prefs.normalized()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(normalized) + "\n")
    }
}
