package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrchestrationPreferencesTest {
    @Test
    fun roleSettingsRoundTripAndNormalize() {
        val prefs = OrchestrationPreferences.Defaults
            .withModel(OrchestrationProviderRole.Impl, "  gpt-5.6-sol  ")
            .withReasoningEffort(OrchestrationProviderRole.Impl, AgentReasoningEffort.High)
            .withAutonomy(OrchestrationProviderRole.Impl, AgentAutonomy.Full)

        val normalized = prefs.normalized()
        assertEquals("gpt-5.6-sol", normalized.settingsFor(OrchestrationProviderRole.Impl).model)
        assertEquals(AgentReasoningEffort.High, normalized.reasoningEffortFor(OrchestrationProviderRole.Impl))
        assertEquals(AgentAutonomy.Full, normalized.autonomyFor(OrchestrationProviderRole.Impl))
        assertNull(normalized.autonomyFor(OrchestrationProviderRole.Audit))
        assertNull(normalized.reasoningEffortFor(OrchestrationProviderRole.Audit))
    }

    @Test
    fun invalidRoleSettingsBecomeUnsetAndUnknownRolesAreDropped() {
        val normalized = OrchestrationPreferences(
            providers = mapOf("impl" to "Codex"),
            settings = mapOf(
                "impl" to OrchestrationRoleSettings(
                    model = " ",
                    reasoningEffort = "not-an-effort",
                    autonomy = "not-a-permission",
                ),
                "unknown" to OrchestrationRoleSettings(model = "ignored", autonomy = "Full"),
            ),
        ).normalized()

        assertEquals(emptySet(), normalized.settings.keys - "impl")
        assertEquals(null, normalized.settingsFor(OrchestrationProviderRole.Impl).model)
        assertNull(normalized.reasoningEffortFor(OrchestrationProviderRole.Impl))
        assertNull(normalized.autonomyFor(OrchestrationProviderRole.Impl))
    }

    @Test
    fun reasoningEffortAcceptsCliValueAndClearsEmptySettings() {
        val withEffort = OrchestrationPreferences(
            settings = mapOf(
                "impl" to OrchestrationRoleSettings(reasoningEffort = "xhigh"),
            ),
        ).normalized()
        assertEquals(AgentReasoningEffort.ExtraHigh, withEffort.reasoningEffortFor(OrchestrationProviderRole.Impl))

        val cleared = withEffort.withReasoningEffort(OrchestrationProviderRole.Impl, null)
        assertEquals(emptyMap(), cleared.settings)
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
        assertEquals(
            "lead (this chat) · workers Cursor",
            orchestrationSkillProviderHint("andy-swarm", prefs),
        )
        assertNull(orchestrationSkillProviderHint("andy-orchestration", prefs))
        assertNull(orchestrationSkillProviderHint("grill-me", prefs))
        assertEquals("uses Cursor", orchestrationSkillProviderHint("/andy-handoff", prefs))
        assertEquals(
            "worker Cursor · verifier Claude Code",
            orchestrationSkillProviderHint("/andy-loop", prefs),
        )
        assertEquals(
            "lead (this chat) · workers Cursor",
            orchestrationSkillProviderHint("/andy-swarm", prefs),
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
        assertEquals(
            "lead (this chat) · workers Codex",
            orchestrationSkillProviderHint("andy-swarm", OrchestrationPreferences()),
        )
    }

    @Test
    fun swarmSettingsNormalizeAndRoundTripThroughWithers() {
        val prefs = OrchestrationPreferences.Defaults
            .withSwarmWorkers(99)
            .withSwarmCleanup(true)
            .withSwarmSkipApproval(true)
            .normalized()

        assertEquals(SwarmOrchestrationSettings.MaxWorkers, prefs.swarm.workers)
        assertEquals(true, prefs.swarm.cleanup)
        assertEquals(true, prefs.swarm.skipApproval)

        val clampedLow = OrchestrationPreferences(
            swarm = SwarmOrchestrationSettings(workers = 0, cleanup = true),
        ).normalized()
        assertEquals(SwarmOrchestrationSettings.MinWorkers, clampedLow.swarm.workers)

        val defaults = OrchestrationPreferences().normalized()
        assertEquals(SwarmOrchestrationSettings.Defaults, defaults.swarm)
    }
}
