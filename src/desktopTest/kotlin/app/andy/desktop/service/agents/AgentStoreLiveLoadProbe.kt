package app.andy.desktop.service.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AgentStoreLiveLoadProbe {
    @Test
    fun loadRealUserStoreOrPrintError() = runBlocking {
        val file = File(System.getProperty("user.home"), ".andy/agents.toml")
        val state = DesktopAgentTaskStore(file).load()
        val withProject = state.tasks.count { it.projectId != null }
        val without = state.tasks.size - withProject
        println("tasks=${state.tasks.size} project-linked=$withProject standalone=$without workflows=${state.projectWorkflows.size}")
        state.projectWorkflows.forEach { (id, wf) -> println("workflow $id tasks=${wf.tasks.size}") }
        assertTrue(state.tasks.isNotEmpty())
    }
}
