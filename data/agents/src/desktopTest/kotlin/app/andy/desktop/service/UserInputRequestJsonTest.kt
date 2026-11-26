package app.andy.desktop.service

import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UserInputRequestJsonTest {
    @Test
    fun serializesClosedAndMultiSelectQuestionFlags() {
        val request = AgentUserInputRequest(
            id = "acp-elicitation-task-1",
            origin = AgentUserInputOrigin.AcpElicitation,
            questions = listOf(
                AgentUserInputQuestion(
                    id = "targets",
                    header = "Targets",
                    question = "Which targets?",
                    options = listOf(
                        AgentUserInputOption("Desktop"),
                        AgentUserInputOption("Web"),
                    ),
                    allowFreeform = false,
                    multiSelect = true,
                ),
            ),
        )
        val question = assertNotNull(userInputRequestJson(request)["questions"]?.jsonArray?.firstOrNull()?.jsonObject)
        assertEquals(false, question["allowFreeform"]?.jsonPrimitive?.boolean)
        assertEquals(true, question["multiSelect"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun serializesPermissionShapeSharedByListAndStatus() {
        val request = AgentUserInputRequest(
            id = "acp-permission-task-1",
            origin = AgentUserInputOrigin.AcpPermission,
            questions = listOf(
                AgentUserInputQuestion(
                    id = "acp-permission-task-1",
                    header = "delete",
                    question = "Delete /tmp/foo.txt?",
                    options = listOf(
                        AgentUserInputOption("Allow", "allow_once · 1"),
                        AgentUserInputOption("Reject", "reject_once · 2"),
                    ),
                ),
            ),
        )
        val json = userInputRequestJson(request)
        assertEquals("acp-permission-task-1", json["id"]?.jsonPrimitive?.content)
        assertEquals("AcpPermission", json["origin"]?.jsonPrimitive?.content)
        val question = assertNotNull(json["questions"]?.jsonArray?.firstOrNull()?.jsonObject)
        assertEquals("delete", question["header"]?.jsonPrimitive?.content)
        assertEquals("Delete /tmp/foo.txt?", question["question"]?.jsonPrimitive?.content)
        assertEquals(2, question["options"]?.jsonArray?.size)
    }
}
