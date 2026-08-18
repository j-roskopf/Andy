package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelProbeTest {
    @Test
    fun malformedUrlIsUnreachableInsteadOfThrowing() {
        val probe = LocalModelProbe()
        val workspace = WorkspaceState(
            ollamaBaseUrl = "not a url",
            lmStudioBaseUrl = "localhost:1234",
        )
        assertNull(probe.query(AgentKind.Ollama, workspace))
        assertNull(probe.query(AgentKind.LMStudio, workspace))
        assertTrue(probe.query(workspace).isEmpty())
        assertTrue(probe.reachable(workspace).values.all { reachable -> !reachable })
    }
}
