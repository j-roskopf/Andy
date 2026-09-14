package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelProbeTest {
    @Test
    fun malformedUrlIsUnreachableInsteadOfThrowing() {
        val probe = LocalModelProbe(openRouterApiKey = { null })
        val workspace = WorkspaceState(
            ollamaBaseUrl = "not a url",
            lmStudioBaseUrl = "localhost:1234",
        )
        assertNull(probe.query(AgentKind.Ollama, workspace))
        assertNull(probe.query(AgentKind.LMStudio, workspace))
        assertTrue(probe.query(workspace).isEmpty())
        assertTrue(probe.reachable(workspace).values.all { reachable -> !reachable })
    }

    @Test
    fun closedLoopbackPortIsUnreachable() {
        val probe = LocalModelProbe(openRouterApiKey = { null })
        val workspace = WorkspaceState(
            ollamaBaseUrl = "http://127.0.0.1:9/v1",
            lmStudioBaseUrl = "http://127.0.0.1:9/v1",
        )
        assertTrue(probe.query(workspace).isEmpty())
        assertTrue(probe.reachable(workspace).values.all { reachable -> !reachable })
    }

    @Test
    fun openRouterWithKeyIsReachableEvenWhenModelsProbeFails() {
        val probe = LocalModelProbe(openRouterApiKey = { "sk-or-test" })
        val workspace = WorkspaceState(
            openRouterBaseUrl = "http://127.0.0.1:9/v1",
        )
        val models = probe.query(AgentKind.OpenRouter, workspace)
        assertTrue(models != null && models.isEmpty())
        assertTrue(probe.reachable(workspace)[AgentKind.OpenRouter] == true)
    }

    @Test
    fun openRouterWithoutKeyIsUnreachable() {
        val probe = LocalModelProbe(openRouterApiKey = { null })
        assertNull(probe.query(AgentKind.OpenRouter, WorkspaceState()))
        assertTrue(probe.reachable(WorkspaceState())[AgentKind.OpenRouter] == false)
    }
}
