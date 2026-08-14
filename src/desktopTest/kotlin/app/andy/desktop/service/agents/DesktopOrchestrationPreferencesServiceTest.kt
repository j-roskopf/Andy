package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentAutonomy
import app.andy.model.OrchestrationPreferences
import app.andy.model.OrchestrationProviderRole
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOrchestrationPreferencesServiceTest {
    @Test
    fun missingFileReturnsDefaultsWithoutWriting() {
        val dir = File.createTempFile("andy-orch-prefs-missing", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val file = File(dir, "orchestration-preferences.json")
            val service = DesktopOrchestrationPreferencesService(file)
            val loaded = service.load()
            assertEquals(OrchestrationPreferences.Defaults, loaded)
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun roundTripPreservesProvidersAndNotes() {
        val dir = File.createTempFile("andy-orch-prefs-roundtrip", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val file = File(dir, "orchestration-preferences.json")
            val service = DesktopOrchestrationPreferencesService(file)
            val saved = OrchestrationPreferences.Defaults
                .withAgent(OrchestrationProviderRole.Impl, AgentKind.ClaudeCode)
                .withAgent(OrchestrationProviderRole.Audit, AgentKind.Cursor)
                .withModel(OrchestrationProviderRole.Impl, "sonnet")
                .withAutonomy(OrchestrationProviderRole.Impl, AgentAutonomy.Full)
                .withPreferenceNotes(listOf("Prefer Claude for UI copy.", "  ", "Codex for mechanical work."))
            service.save(saved)
            assertTrue(file.isFile)
            val loaded = service.load()
            assertEquals(AgentKind.ClaudeCode, loaded.agentFor(OrchestrationProviderRole.Impl))
            assertEquals(AgentKind.Cursor, loaded.agentFor(OrchestrationProviderRole.Audit))
            assertEquals(AgentKind.ClaudeCode, loaded.agentFor(OrchestrationProviderRole.Ui))
            assertEquals("sonnet", loaded.settingsFor(OrchestrationProviderRole.Impl).model)
            assertEquals(AgentAutonomy.Full, loaded.autonomyFor(OrchestrationProviderRole.Impl))
            assertEquals(
                listOf("Prefer Claude for UI copy.", "Codex for mechanical work."),
                loaded.preferences,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidJsonAndUnknownAgentsFallBackToDefaults() {
        val dir = File.createTempFile("andy-orch-prefs-bad", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val file = File(dir, "orchestration-preferences.json")
            file.writeText("{ not json")
            val service = DesktopOrchestrationPreferencesService(file)
            assertEquals(OrchestrationPreferences.Defaults, service.load())

            file.writeText(
                """
                {
                  "providers": {
                    "impl": "NotARealAgent",
                    "audit": "claudecode"
                  },
                  "preferences": ["keep me"]
                }
                """.trimIndent(),
            )
            val loaded = service.load()
            assertEquals(AgentKind.Codex, loaded.agentFor(OrchestrationProviderRole.Impl))
            assertEquals(AgentKind.ClaudeCode, loaded.agentFor(OrchestrationProviderRole.Audit))
            assertEquals(listOf("keep me"), loaded.preferences)
        } finally {
            dir.deleteRecursively()
        }
    }
}
