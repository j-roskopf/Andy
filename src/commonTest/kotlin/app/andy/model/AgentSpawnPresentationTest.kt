package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSpawnPresentationTest {
    @Test
    fun detectsCursorAndAndySpawnTools() {
        assertTrue(AgentSpawnPresentation.isAgentSpawnTool("Task"))
        assertTrue(AgentSpawnPresentation.isAgentSpawnTool("Subagent"))
        assertTrue(AgentSpawnPresentation.isAgentSpawnTool("chat.start"))
        assertTrue(AgentSpawnPresentation.isAgentSpawnTool("Andy MCP · chat start"))
        assertTrue(AgentSpawnPresentation.isAgentSpawnTool("mcp_andy_chat_start"))
        assertFalse(AgentSpawnPresentation.isAgentSpawnTool("Grep"))
        assertFalse(AgentSpawnPresentation.isAgentSpawnTool("Andy MCP · tap"))
    }

    @Test
    fun spawningHeadlineMatchesCursorCopy() {
        assertEquals("Spawning agent", AgentSpawnPresentation.spawningHeadline(1))
        assertEquals("Spawning 4 agents", AgentSpawnPresentation.spawningHeadline(4))
    }

    @Test
    fun parsesCursorTaskJsonPayload() {
        val detail = """
            {"description":"Archimedes","prompt":"Review PR #717 for security regressions in auth","subagent_type":"explore"}
        """.trimIndent()

        val spawn = AgentSpawnPresentation.parse("Task", summary = "", detail = detail)

        assertEquals("Archimedes", spawn.name)
        assertEquals("explorer", spawn.type)
        assertEquals("Review PR #717 for security regressions in auth", spawn.instructions)
    }

    @Test
    fun parsesCursorTaskSummaryKeyValues() {
        val summary =
            "description=Review PR #717, prompt=Review PR #717 across auth and billing, subagent_type=explore"

        val spawn = AgentSpawnPresentation.parse("Task", summary = summary, detail = summary)

        assertEquals("Review PR #717", spawn.name)
        assertEquals("explorer", spawn.type)
        assertEquals("Review PR #717 across auth and billing", spawn.instructions)
    }

    @Test
    fun parsesAndyChatStartPayload() {
        val detail = """
            {"prompt":"Second opinion on the transcript spawn UI","agent":"Codex","title":"Advisor"}
        """.trimIndent()

        val spawn = AgentSpawnPresentation.parse(
            toolName = "Andy MCP · chat start",
            summary = "prompt=Second opinion on the transcript spawn UI, agent=Codex, title=Advisor",
            detail = detail,
        )

        assertEquals("Advisor", spawn.name)
        assertEquals("Codex", spawn.type)
        assertEquals("Second opinion on the transcript spawn UI", spawn.instructions)
    }

    @Test
    fun prefersExplicitNameOverDescription() {
        val detail = """
            {"name":"Huygens","description":"Explore billing","prompt":"Map billing entry points","subagent_type":"explore"}
        """.trimIndent()

        val spawn = AgentSpawnPresentation.parse("Task", summary = "", detail = detail)

        assertEquals("Huygens", spawn.name)
        assertEquals("explorer", spawn.type)
        assertEquals("Map billing entry points", spawn.instructions)
    }

    @Test
    fun stillDetectsSpawnAfterTitleBecomesPersonaName() {
        val detail =
            """{"description":"Review PR #717","prompt":"Review PR #717","subagent_type":"explore"}"""
        assertTrue(AgentSpawnPresentation.isAgentSpawn("Archimedes", summary = "", detail = detail))
        val spawn = AgentSpawnPresentation.parse("Archimedes", summary = "", detail = detail)
        assertEquals("Archimedes", spawn.name)
        assertEquals("explorer", spawn.type)
    }

    @Test
    fun parsesTaskIdFromChatStartResult() {
        val detail = """
            {"prompt":"Second opinion","agent":"Codex","title":"Advisor"}
            {"id":"task-abc1234567","status":"Working","tmuxSession":"andy-task-abc1234567"}
        """.trimIndent()

        val spawn = AgentSpawnPresentation.parse(
            toolName = "Andy MCP · chat start",
            summary = "id=task-abc1234567, status=Working",
            detail = detail,
        )

        assertEquals("Advisor", spawn.name)
        assertEquals("task-abc1234567", spawn.taskId)
    }

    @Test
    fun pairsToolResultIdOntoSpawnSource() {
        val events = listOf(
            AgentEvent.ToolCall(
                atMillis = 1,
                toolName = "Andy MCP · chat start",
                summary = "prompt=Review the diff, agent=Codex, title=Huygens",
                detail = """{"prompt":"Review the diff","agent":"Codex","title":"Huygens"}""",
            ),
            AgentEvent.ToolResult(
                atMillis = 2,
                toolName = "Andy MCP · chat start",
                summary = """{"id":"task-deadbeef01","status":"Working"}""",
                detail = """{"id":"task-deadbeef01","status":"Working"}""",
                isError = false,
            ),
        )

        val source = AgentSpawnPresentation.spawnSources(events).single()
        val spawn = AgentSpawnPresentation.parse(source.toolName, source.summary, source.detail)
        assertEquals("Huygens", spawn.name)
        assertEquals("task-deadbeef01", spawn.taskId)
    }

    @Test
    fun resolveTaskIdFallsBackToTitleMatch() {
        val spawn = AgentSpawnPresentation.Spawn(
            name = "Archimedes",
            type = "explorer",
            instructions = "Review PR #717",
        )
        val tasks = listOf(
            AgentTask(
                id = "task-parent0001",
                title = "Parent",
                prompt = "orchestrate",
                agent = AgentKind.Cursor,
                createdAtMillis = 1,
            ),
            AgentTask(
                id = "task-child00001",
                title = "Archimedes",
                prompt = "Review PR #717 for auth",
                agent = AgentKind.Codex,
                createdAtMillis = 2,
            ),
        )

        assertEquals(
            "task-child00001",
            AgentSpawnPresentation.resolveTaskId(spawn, tasks, excludeTaskId = "task-parent0001"),
        )
    }
}
