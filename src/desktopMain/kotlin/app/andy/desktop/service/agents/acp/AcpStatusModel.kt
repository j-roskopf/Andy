package app.andy.desktop.service.agents.acp

import app.andy.desktop.service.agents.AgentStatusSnapshot
import app.andy.model.AgentStatus

/** ACP has explicit turn boundaries, so every published snapshot is confident. */
object AcpStatusModel {
    fun working(): AgentStatusSnapshot = AgentStatusSnapshot(AgentStatus.Working, confident = true)

    fun blocked(): AgentStatusSnapshot = AgentStatusSnapshot(AgentStatus.Blocked, confident = true)

    fun fromStopReason(stopReason: String?): AgentStatusSnapshot = when (stopReason?.lowercase()) {
        "cancelled", "canceled" -> AgentStatusSnapshot(AgentStatus.Done, confident = true)
        "end_turn", "end-turn", "refusal", "max_tokens", "max_turn_requests", "max-turn-requests" ->
            AgentStatusSnapshot(AgentStatus.Done, confident = true)
        null, "" -> AgentStatusSnapshot(AgentStatus.Error, confident = true)
        else -> AgentStatusSnapshot(AgentStatus.Done, confident = true)
    }

    fun error(): AgentStatusSnapshot = AgentStatusSnapshot(AgentStatus.Error, confident = true)
}
