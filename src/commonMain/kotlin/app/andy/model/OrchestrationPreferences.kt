package app.andy.model

import kotlinx.serialization.Serializable

/** Role keys in `~/.andy/orchestration-preferences.json` used by andy-loop / handoff / committee. */
enum class OrchestrationProviderRole(
    val key: String,
    val label: String,
) {
    Impl("impl", "Implementation (worker)"),
    Ui("ui", "UI / design"),
    Research("research", "Research"),
    Planning("planning", "Planning"),
    Audit("audit", "Audit (verifier)"),
}

/**
 * On-disk shape for `~/.andy/orchestration-preferences.json`.
 * Skills read this file directly; the Settings UI loads and saves the same path.
 */
@Serializable
data class OrchestrationRoleSettings(
    /** Null keeps the selected provider's default model. */
    val model: String? = null,
    /** Null keeps the parent task's permission dial (or Standard for a root task). */
    val autonomy: String? = null,
) {
    fun normalized(): OrchestrationRoleSettings = copy(
        model = model?.trim()?.takeIf { it.isNotEmpty() },
        autonomy = autonomy
            ?.trim()
            ?.let { value -> AgentAutonomy.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.name },
    )
}

@Serializable
data class OrchestrationPreferences(
    val providers: Map<String, String> = emptyMap(),
    /** Optional model/permission overrides keyed by role, e.g. `impl` or `audit`. */
    val settings: Map<String, OrchestrationRoleSettings> = emptyMap(),
    val preferences: List<String> = emptyList(),
) {
    fun agentFor(role: OrchestrationProviderRole): AgentKind {
        val raw = providers[role.key] ?: Defaults.providers[role.key]
        return parseAgentKind(raw) ?: parseAgentKind(Defaults.providers[role.key]) ?: AgentKind.Codex
    }

    fun withAgent(role: OrchestrationProviderRole, kind: AgentKind): OrchestrationPreferences {
        val merged = Defaults.providers + providers + (role.key to kind.name)
        return copy(
            providers = OrchestrationProviderRole.entries.associate { entry ->
                entry.key to (parseAgentKind(merged[entry.key])?.name ?: Defaults.providers.getValue(entry.key))
            },
        )
    }

    fun settingsFor(role: OrchestrationProviderRole): OrchestrationRoleSettings =
        settings[role.key]?.normalized() ?: OrchestrationRoleSettings()

    fun autonomyFor(role: OrchestrationProviderRole): AgentAutonomy? =
        settingsFor(role).autonomy?.let { value ->
            AgentAutonomy.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

    fun withSettings(
        role: OrchestrationProviderRole,
        roleSettings: OrchestrationRoleSettings,
    ): OrchestrationPreferences {
        val normalized = roleSettings.normalized()
        val next = settings.toMutableMap()
        if (normalized.model == null && normalized.autonomy == null) {
            next.remove(role.key)
        } else {
            next[role.key] = normalized
        }
        return copy(settings = next)
    }

    fun withModel(role: OrchestrationProviderRole, model: String?): OrchestrationPreferences =
        withSettings(role, settingsFor(role).copy(model = model))

    fun withAutonomy(role: OrchestrationProviderRole, autonomy: AgentAutonomy?): OrchestrationPreferences =
        withSettings(role, settingsFor(role).copy(autonomy = autonomy?.name))

    fun withPreferenceNotes(notes: List<String>): OrchestrationPreferences =
        copy(preferences = notes.map { it.trim() }.filter { it.isNotEmpty() })

    fun normalized(): OrchestrationPreferences {
        val providers = OrchestrationProviderRole.entries.associate { role ->
            val raw = this.providers[role.key] ?: Defaults.providers.getValue(role.key)
            role.key to (parseAgentKind(raw)?.name ?: Defaults.providers.getValue(role.key))
        }
        return copy(
            providers = providers,
            settings = settings
                .filterKeys { key -> OrchestrationProviderRole.entries.any { it.key == key } }
                .mapValues { (_, value) -> value.normalized() },
            preferences = preferences.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    companion object {
        val Defaults = OrchestrationPreferences(
            providers = mapOf(
                OrchestrationProviderRole.Impl.key to AgentKind.Codex.name,
                OrchestrationProviderRole.Ui.key to AgentKind.ClaudeCode.name,
                OrchestrationProviderRole.Research.key to AgentKind.ClaudeCode.name,
                OrchestrationProviderRole.Planning.key to AgentKind.Codex.name,
                OrchestrationProviderRole.Audit.key to AgentKind.Codex.name,
            ),
        )

        private fun parseAgentKind(raw: String?): AgentKind? =
            raw?.let { name -> AgentKind.entries.find { it.name.equals(name, ignoreCase = true) } }
    }
}
