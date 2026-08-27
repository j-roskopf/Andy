package app.andy.desktop.service.agents

import app.andy.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class AppendAgentStatusTest {
    @Test
    fun appendAgentStatusWritesJsonLine() {
        val dir = File.createTempFile("andy-status-append", null).also { it.delete(); it.mkdirs() }
        try {
            appendAgentStatus(dir, AgentStatus.Blocked)
            val lines = File(dir, "status.json").readLines().filter { it.isNotBlank() }
            assertEquals(1, lines.size)
            assertTrue("blocked" in lines.single().lowercase())
        } finally {
            dir.deleteRecursively()
        }
    }
}
