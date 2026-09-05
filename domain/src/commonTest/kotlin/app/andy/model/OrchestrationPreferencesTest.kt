package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrchestrationPreferencesTest {
    @Test
    fun roleSettingsRoundTripAndNormalize() {
        val prefs = OrchestrationPreferences.Defaults
            .withModel(OrchestrationProviderRole.Impl, "  gpt-5.6-sol  ")
            .withAutonomy(OrchestrationProviderRole.Impl, AgentAutonomy.Full)

        val normalized = prefs.normalized()
        assertEquals("gpt-5.6-sol", normalized.settingsFor(OrchestrationProviderRole.Impl).model)
        assertEquals(AgentAutonomy.Full, normalized.autonomyFor(OrchestrationProviderRole.Impl))
        assertNull(normalized.autonomyFor(OrchestrationProviderRole.Audit))
    }

    @Test
    fun invalidRoleSettingsBecomeUnsetAndUnknownRolesAreDropped() {
        val normalized = OrchestrationPreferences(
            providers = mapOf("impl" to "Codex"),
            settings = mapOf(
                "impl" to OrchestrationRoleSettings(model = " ", autonomy = "not-a-permission"),
                "unknown" to OrchestrationRoleSettings(model = "ignored", autonomy = "Full"),
            ),
        ).normalized()

        assertEquals(emptySet(), normalized.settings.keys - "impl")
        assertEquals(null, normalized.settingsFor(OrchestrationProviderRole.Impl).model)
        assertNull(normalized.autonomyFor(OrchestrationProviderRole.Impl))
    }

    @Test
    fun orchestrationSkillProviderHintReflectsConfiguredRoles() {
        val prefs = OrchestrationPreferences.Defaults
            .withAgent(OrchestrationProviderRole.Impl, AgentKind.Cursor)
            .withAgent(OrchestrationProviderRole.Audit, AgentKind.ClaudeCode)
            .withAgent(OrchestrationProviderRole.Planning, AgentKind.Codex)
            .withAgent(OrchestrationProviderRole.Research, AgentKind.Antigravity)

        assertEquals("uses Cursor", orchestrationSkillProviderHint("andy-handoff", prefs))
        assertEquals(
            "worker Cursor · verifier Claude Code",
            orchestrationSkillProviderHint("andy-loop", prefs),
        )
        assertEquals(
            "planning Codex · audit Claude Code · research Antigravity",
            orchestrationSkillProviderHint("ANDY-ADVISOR", prefs),
        )
        assertEquals(
            "planning Codex · research Antigravity · audit Claude Code",
            orchestrationSkillProviderHint("andy-committee", prefs),
        )
        assertNull(orchestrationSkillProviderHint("andy-orchestration", prefs))
        assertNull(orchestrationSkillProviderHint("grill-me", prefs))
        assertEquals("uses Cursor", orchestrationSkillProviderHint("/andy-handoff", prefs))
        assertEquals(
            "worker Cursor · verifier Claude Code",
            orchestrationSkillProviderHint("/andy-loop", prefs),
        )
    }

    @Test
    fun orchestrationSkillProviderHintUsesDefaultsWhenPrefsEmpty() {
        assertEquals(
            "uses Codex",
            orchestrationSkillProviderHint("andy-handoff", OrchestrationPreferences()),
        )
        assertEquals(
            "worker Codex · verifier Codex",
            orchestrationSkillProviderHint("andy-loop", OrchestrationPreferences()),
        )
    }
}
