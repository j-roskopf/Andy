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
data class OrchestrationPreferences(
    val providers: Map<String, String> = emptyMap(),
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

    fun withPreferenceNotes(notes: List<String>): OrchestrationPreferences =
        copy(preferences = notes.map { it.trim() }.filter { it.isNotEmpty() })

    fun normalized(): OrchestrationPreferences {
        val providers = OrchestrationProviderRole.entries.associate { role ->
            val raw = this.providers[role.key] ?: Defaults.providers.getValue(role.key)
            role.key to (parseAgentKind(raw)?.name ?: Defaults.providers.getValue(role.key))
        }
        return copy(
            providers = providers,
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
