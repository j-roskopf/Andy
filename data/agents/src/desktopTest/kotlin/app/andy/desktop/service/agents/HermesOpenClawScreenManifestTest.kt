package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import kotlin.test.Test
import kotlin.test.assertEquals

class HermesOpenClawScreenManifestTest {
    @Test
    fun hermesApprovalAndPromptStates() {
        assertEquals(ScreenState.Blocked, evaluateScreenManifest(AgentKind.Hermes, DetectionInput("Approve tool? [y/n]")).state)
        assertEquals(ScreenState.Idle, evaluateScreenManifest(AgentKind.Hermes, DetectionInput("\n❯")).state)
    }

    @Test
    fun openClawThinkingAndPromptStates() {
        assertEquals(ScreenState.Working, evaluateScreenManifest(AgentKind.OpenClaw, DetectionInput("thinking...")).state)
        assertEquals(ScreenState.Idle, evaluateScreenManifest(AgentKind.OpenClaw, DetectionInput("\n❯")).state)
    }
}
