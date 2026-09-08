package app.andy.desktop.service.agents

import app.andy.model.AgentTask
import app.andy.model.ProviderAuthRecovery
import app.andy.model.providerAuthRecoveryOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun ProviderAuthRecovery.toJsonObject(): JsonObject = buildJsonObject {
    put("needed", true)
    put("agent", agent.name)
    put("command", command)
    put("instructions", instructions)
    put("remoteInstructions", remoteInstructions)
}

internal fun AgentTask.providerAuthRecoveryJson(): JsonObject? =
    providerAuthRecoveryOrNull()?.toJsonObject()
