@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package app.andy.desktop.service.agents.acp

import app.andy.model.AgentUserInputOrigin
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationFormCapabilities
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationPropertySchema
import com.agentclientprotocol.model.ElicitationSchema
import com.agentclientprotocol.model.ElicitationSchemaType
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.EnumOption
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MultiSelectItems
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.TitledMultiSelectItems
import com.agentclientprotocol.model.ToolCallId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpElicitationBridgeTest {
    private fun choiceProperty(
        title: String,
        description: String,
        choices: List<String> = listOf("Desktop only", "Desktop and web"),
    ) = ElicitationPropertySchema.StringProperty(
        title = title,
        description = description,
        oneOf = choices.map { EnumOption(it, it) },
    )

    private fun request(
        properties: Map<String, ElicitationPropertySchema>,
        message: String = "Questions",
        toolCallId: String? = "call-1",
    ) = CreateElicitationRequest(
        scope = ElicitationScope.Session(SessionId("session-1"), toolCallId?.let { ToolCallId(it) }),
        mode = ElicitationMode.Form(ElicitationSchema(type = ElicitationSchemaType.OBJECT, properties = properties)),
        message = message,
    )

    private fun acceptedContent(action: ElicitationAction): Map<String, ElicitationContentValue> =
        assertIs<ElicitationAction.Accept>(action).content.orEmpty()

    @Test
    fun questionFormBecomesDecisionCardAndSelectAnswer() {
        val prompt = acpElicitationPrompt(
            request(
                linkedMapOf(
                    "q0" to choiceProperty("Platform", "Which platforms should ship?"),
                    "q0_custom" to choiceProperty(
                        "Platform (other)",
                        "Type your own answer",
                        choices = emptyList(),
                    ),
                ),
            ),
            taskId = "task-1",
        )!!

        assertEquals(AgentUserInputOrigin.AcpElicitation, prompt.request.origin)
        assertEquals("acp-elicitation-task-1-call-1", prompt.request.id)
        assertEquals(1, prompt.request.questions.size)
        val card = prompt.request.questions.single()
        assertEquals("Platform", card.header)
        assertEquals("Which platforms should ship?", card.question)
        assertEquals(listOf("Desktop only", "Desktop and web"), card.options.map { it.label })
        assertTrue(card.allowFreeform)
        assertFalse(card.multiSelect)

        val content = acceptedContent(prompt.response(mapOf("q0" to "Desktop and web")).action)
        assertEquals(ElicitationContentValue.StringValue("Desktop and web"), content["q0"])
        assertNull(content["q0_custom"])
    }

    @Test
    fun freeformOtherAnswerMapsToTheCustomCompanionKey() {
        val prompt = acpElicitationPrompt(
            request(
                linkedMapOf(
                    "q0" to choiceProperty("Platform", "Which platforms should ship?"),
                    "q0_custom" to choiceProperty(
                        "Platform (other)",
                        "Type your own answer",
                        choices = emptyList(),
                    ),
                ),
            ),
            taskId = "task-1",
        )!!

        val content = acceptedContent(prompt.response(mapOf("q0" to "iOS and Android")).action)
        assertEquals(ElicitationContentValue.StringValue("iOS and Android"), content["q0_custom"])
        assertNull(content["q0"])
    }

    @Test
    fun multiselectQuestionSendsAnArray() {
        val prompt = acpElicitationPrompt(
            request(
                linkedMapOf(
                    "q0" to ElicitationPropertySchema.ArrayProperty(
                        title = "Targets",
                        description = "Pick a target",
                        items = MultiSelectItems.Titled(
                            TitledMultiSelectItems(
                                options = listOf(
                                    EnumOption("desktop", "Desktop"),
                                    EnumOption("web", "Web"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            taskId = "task-1",
        )!!

        assertTrue(prompt.request.questions.single().multiSelect)
        val content = acceptedContent(prompt.response(mapOf("q0" to "Web")).action)
        assertEquals(ElicitationContentValue.StringArrayValue(listOf("web")), content["q0"])
    }

    @Test
    fun multiselectKeepsEverySelection() {
        val prompt = acpElicitationPrompt(
            request(
                linkedMapOf(
                    "q0" to ElicitationPropertySchema.ArrayProperty(
                        title = "Targets",
                        description = "Pick targets",
                        items = MultiSelectItems.Titled(
                            TitledMultiSelectItems(
                                options = listOf(
                                    EnumOption("desktop", "Desktop"),
                                    EnumOption("web", "Web"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            taskId = "task-1",
        )!!

        val answers = mapOf("q0" to "Desktop\nWeb")
        assertTrue(prompt.isComplete(answers))
        val content = acceptedContent(prompt.response(answers).action)
        assertEquals(ElicitationContentValue.StringArrayValue(listOf("desktop", "web")), content["q0"])
    }

    @Test
    fun closedEnumRejectsFreeformAndDoesNotOfferIt() {
        val prompt = acpElicitationPrompt(
            request(linkedMapOf("q0" to choiceProperty("Platform", "Pick one"))),
            taskId = "task-1",
        )!!
        val card = prompt.request.questions.single()
        assertFalse(card.allowFreeform)
        assertFalse(card.multiSelect)

        assertTrue(prompt.isComplete(mapOf("q0" to "Desktop only")))
        assertFalse(prompt.isComplete(mapOf("q0" to "Something else")))
    }

    @Test
    fun booleanQuestionUsesItsOwnOptionsInsteadOfFreeform() {
        val prompt = acpElicitationPrompt(
            request(
                linkedMapOf(
                    "q0" to ElicitationPropertySchema.BooleanProperty(
                        title = "Ship it?",
                        description = "Approve the release",
                    ),
                ),
            ),
            taskId = "task-1",
        )!!
        val card = prompt.request.questions.single()
        assertFalse(card.allowFreeform)
        assertEquals(listOf("Yes", "No"), card.options.map { it.label })
    }

    @Test
    fun multipleQuestionsPreserveSchemaOrder() {
        val prompt = acpElicitationPrompt(
            request(
                linkedMapOf(
                    "q0" to choiceProperty("First", "First question"),
                    "q0_custom" to choiceProperty("First (other)", "Type your own answer", choices = emptyList()),
                    "q1" to choiceProperty("Second", "Second question"),
                    "q1_custom" to choiceProperty("Second (other)", "Type your own answer", choices = emptyList()),
                ),
            ),
            taskId = "task-1",
        )!!

        assertEquals(listOf("q0", "q1"), prompt.request.questions.map { it.id })
    }

    @Test
    fun requestSuspendsUntilTheUserAnswers() = runBlocking {
        var pending: PendingAcpElicitation? = null
        var resolvedId: String? = null
        val bridge = AcpElicitationBridge(
            taskId = "task-1",
            onPending = { pending = it },
            onResolved = { requestId, _ -> resolvedId = requestId },
        )
        val form = request(
            linkedMapOf(
                "q0" to choiceProperty("Platform", "Which platforms should ship?"),
                "q0_custom" to choiceProperty("Platform (other)", "Type your own answer", choices = emptyList()),
            ),
        )

        coroutineScope {
            val deferred = async { bridge.request(form) }
            while (pending == null) yield()
            val shown = checkNotNull(pending)
            assertEquals(AgentUserInputOrigin.AcpElicitation, shown.request.origin)
            assertTrue(bridge.respond(shown.request.id, mapOf("q0" to "Desktop only")))
            val content = acceptedContent(deferred.await().action)
            assertEquals(ElicitationContentValue.StringValue("Desktop only"), content["q0"])
            assertEquals(shown.request.id, resolvedId)
        }
    }

    @Test
    fun incompleteAnswersAreRejected() = runBlocking {
        var pending: PendingAcpElicitation? = null
        val bridge = AcpElicitationBridge(
            taskId = "task-1",
            onPending = { pending = it },
            onResolved = { _, _ -> },
        )
        coroutineScope {
            val deferred = async {
                bridge.request(request(linkedMapOf("q0" to choiceProperty("Platform", "Pick"))))
            }
            while (pending == null) yield()
            assertEquals(false, bridge.respond(pending.request.id, mapOf("q0" to " ")))
            assertTrue(bridge.respond(pending.request.id, mapOf("q0" to "Desktop only")))
            deferred.await()
            Unit
        }
    }

    @Test
    fun decodesOpenCodeQuestionElicitationWireFormat() {
        val json = """
            {
              "sessionId": "session-1",
              "toolCallId": "call-9",
              "mode": "form",
              "message": "Questions",
              "requestedSchema": {
                "type": "object",
                "properties": {
                  "q0": {
                    "type": "string",
                    "title": "Platform",
                    "description": "Which platforms should ship?",
                    "oneOf": [
                      { "const": "Desktop only", "title": "Desktop only" },
                      { "const": "Desktop and web", "title": "Desktop and web" }
                    ]
                  },
                  "q0_custom": {
                    "type": "string",
                    "title": "Platform (other)",
                    "description": "Type your own answer"
                  }
                }
              }
            }
        """.trimIndent()

        val request = Json { ignoreUnknownKeys = true }
            .decodeFromString(CreateElicitationRequest.serializer(), json)
        val prompt = acpElicitationPrompt(request, taskId = "task-1")!!

        assertEquals("acp-elicitation-task-1-call-9", prompt.request.id)
        assertEquals("Which platforms should ship?", prompt.request.questions.single().question)
        val content = acceptedContent(prompt.response(mapOf("q0" to "Desktop only")).action)
        assertEquals(ElicitationContentValue.StringValue("Desktop only"), content["q0"])
    }

    @Test
    fun unknownSchemaWithoutPropertiesIsDeclined() {
        assertNull(acpElicitationPrompt(request(emptyMap()), taskId = "task-1"))
    }

    @Test
    fun clientCapabilitiesAdvertiseFormElicitation() {
        val info = ClientInfo(
            capabilities = ClientCapabilities(
                elicitation = ElicitationCapabilities(form = ElicitationFormCapabilities()),
            ),
            implementation = Implementation("Andy", "desktop", "Andy ACP client"),
        )
        val json = Json.parseToJsonElement(Json.encodeToString(ClientInfo.serializer(), info)).jsonObject
        val elicitation = json["capabilities"]?.jsonObject?.get("elicitation")?.jsonObject
        assertNotNull(elicitation)
        assertNotNull(elicitation["form"])
    }
}
