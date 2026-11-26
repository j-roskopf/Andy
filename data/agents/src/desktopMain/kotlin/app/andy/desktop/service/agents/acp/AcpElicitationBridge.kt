@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package app.andy.desktop.service.agents.acp

import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputOrigin
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationMode
import com.agentclientprotocol.model.ElicitationPropertySchema
import com.agentclientprotocol.model.ElicitationScope
import com.agentclientprotocol.model.EnumOption
import com.agentclientprotocol.model.MultiSelectItems
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** A form elicitation surfaced through Andy's decision card. */
data class PendingAcpElicitation(
    val request: AgentUserInputRequest,
)

internal enum class AcpElicitationFieldKind {
    Text,
    Select,
    MultiSelect,
    Boolean,
    Integer,
    Number,
}

internal data class AcpElicitationOption(
    /** Wire value the provider expects (JSON `const`, not the human label). */
    val value: String,
    val label: String,
)

internal data class AcpElicitationField(
    val questionId: String,
    val schemaKey: String,
    val customKey: String?,
    val kind: AcpElicitationFieldKind,
    val options: List<AcpElicitationOption>,
)

/** The provider-neutral prompt plus the wire mapping needed to answer the elicitation. */
internal data class AcpElicitationPrompt(
    val request: AgentUserInputRequest,
    val fields: List<AcpElicitationField>,
) {
    fun isComplete(answers: Map<String, String>): Boolean =
        request.questions.all { question -> !answers[question.id].isNullOrBlank() }

    fun response(answers: Map<String, String>): CreateElicitationResponse {
        val content = linkedMapOf<String, ElicitationContentValue>()
        fields.forEach { field ->
            val answer = answers[field.questionId]?.trim().orEmpty()
            if (answer.isEmpty()) return@forEach
            when (field.kind) {
                AcpElicitationFieldKind.Text ->
                    content[field.schemaKey] = ElicitationContentValue.StringValue(answer)

                AcpElicitationFieldKind.Select -> {
                    val selected = field.options.firstOrNull { it.label == answer }?.value
                    when {
                        selected != null -> content[field.schemaKey] = ElicitationContentValue.StringValue(selected)
                        field.customKey != null ->
                            content[field.customKey] = ElicitationContentValue.StringValue(answer)

                        else -> content[field.schemaKey] = ElicitationContentValue.StringValue(answer)
                    }
                }

                AcpElicitationFieldKind.MultiSelect -> {
                    val selected = field.options.firstOrNull { it.label == answer }?.value
                    when {
                        selected != null ->
                            content[field.schemaKey] = ElicitationContentValue.StringArrayValue(listOf(selected))

                        field.customKey != null ->
                            content[field.customKey] = ElicitationContentValue.StringValue(answer)

                        else ->
                            content[field.schemaKey] = ElicitationContentValue.StringArrayValue(listOf(answer))
                    }
                }

                AcpElicitationFieldKind.Boolean -> content[field.schemaKey] = ElicitationContentValue.BooleanValue(
                    answer.equals("yes", ignoreCase = true) ||
                        answer.equals("true", ignoreCase = true) ||
                        answer == "1",
                )

                AcpElicitationFieldKind.Integer -> answer.toLongOrNull()?.let { value ->
                    content[field.schemaKey] = ElicitationContentValue.IntegerValue(value)
                }

                AcpElicitationFieldKind.Number -> answer.toDoubleOrNull()?.let { value ->
                    content[field.schemaKey] = ElicitationContentValue.NumberValue(value)
                }
            }
        }
        return CreateElicitationResponse(ElicitationAction.Accept(content), null)
    }
}

/**
 * Bridges ACP `elicitation/create` forms into Andy's decision card. Providers such as
 * OpenCode route their `question` tool (and similar forms) through this channel, so
 * advertising the capability without answering here would leave those tools unusable.
 */
class AcpElicitationBridge(
    private val taskId: String,
    private val onPending: (PendingAcpElicitation) -> Unit,
    private val onResolved: (String, Map<String, String>) -> Unit,
) {
    private data class Entry(
        val prompt: AcpElicitationPrompt,
        val deferred: CompletableDeferred<CreateElicitationResponse>,
    )

    private val pending = mutableMapOf<String, Entry>()

    suspend fun request(request: CreateElicitationRequest): CreateElicitationResponse {
        var prompt = acpElicitationPrompt(request, taskId) ?: return declined()
        val deferred = CompletableDeferred<CreateElicitationResponse>()
        synchronized(pending) {
            // Parallel tool calls can reuse the provider's tool-call id.
            if (prompt.request.id in pending) {
                prompt = prompt.copy(
                    request = prompt.request.copy(id = "${prompt.request.id}-${UUID.randomUUID()}"),
                )
            }
            pending[prompt.request.id] = Entry(prompt, deferred)
        }
        onPending(PendingAcpElicitation(prompt.request))
        return deferred.await()
    }

    fun respond(requestId: String, answers: Map<String, String>): Boolean {
        val entry = synchronized(pending) { pending[requestId] } ?: return false
        if (!entry.prompt.isComplete(answers)) return false
        synchronized(pending) { pending.remove(requestId) }
        onResolved(requestId, answers)
        return entry.deferred.complete(entry.prompt.response(answers))
    }

    fun cancelAll() {
        val entries = synchronized(pending) {
            val current = pending.values.toList()
            pending.clear()
            current
        }
        entries.forEach { entry ->
            entry.deferred.complete(declined())
        }
    }

    private fun declined() = CreateElicitationResponse(ElicitationAction.Decline, null)
}

/**
 * Maps an ACP form elicitation onto Andy's decision card. Returns null when the request
 * is not a form we can represent; the caller then declines so the provider can continue.
 */
internal fun acpElicitationPrompt(
    request: CreateElicitationRequest,
    taskId: String,
): AcpElicitationPrompt? {
    val form = request.mode as? ElicitationMode.Form ?: return null
    val properties = form.requestedSchema.properties
    if (properties.isEmpty()) return null

    val consumed = mutableSetOf<String>()
    val fields = mutableListOf<AcpElicitationField>()
    val questions = mutableListOf<AgentUserInputQuestion>()
    val message = request.message.trim().takeIf { it.isNotEmpty() }
        ?: "The agent needs your input."

    properties.forEach { (key, property) ->
        if (key in consumed) return@forEach
        // Pair a freeform companion with its base even when providers list it first.
        if (key.endsWith(CUSTOM_SUFFIX) && key.removeSuffix(CUSTOM_SUFFIX) in properties) return@forEach
        val customKey = customCompanionKey(key, properties).also { companion ->
            if (companion != null) consumed += companion
        }
        val options = property.options()
        val header = property.title()?.trim().orEmpty()
        val question = property.description()?.trim()?.takeIf { it.isNotEmpty() }
            ?: header.takeIf { it.isNotEmpty() }
            ?: message
        fields += AcpElicitationField(
            questionId = key,
            schemaKey = key,
            customKey = customKey,
            kind = property.kind(options),
            options = options,
        )
        questions += AgentUserInputQuestion(
            id = key,
            header = header.takeIf { it != question }.orEmpty(),
            question = question,
            options = options.map { option ->
                AgentUserInputOption(option.label, "")
            },
        )
    }

    if (questions.isEmpty()) return null
    return AcpElicitationPrompt(
        request = AgentUserInputRequest(
            id = elicitationRequestId(taskId, request),
            questions = questions,
            origin = AgentUserInputOrigin.AcpElicitation,
        ),
        fields = fields,
    )
}

private fun elicitationRequestId(taskId: String, request: CreateElicitationRequest): String {
    val toolCallId = (request.scope as? ElicitationScope.Session)?.toolCallId?.toString()?.trim()
    return if (toolCallId.isNullOrEmpty()) {
        "acp-elicitation-$taskId-${UUID.randomUUID()}"
    } else {
        "acp-elicitation-$taskId-$toolCallId"
    }
}

private const val CUSTOM_SUFFIX = "_custom"

/** OpenCode's question fields ship a `${key}_custom` companion for freeform answers. */
private fun customCompanionKey(
    key: String,
    properties: Map<String, ElicitationPropertySchema>,
): String? {
    val candidate = "$key$CUSTOM_SUFFIX"
    if (candidate !in properties) return null
    val base = properties[key]
    val companion = properties[candidate]
    // Only pair values that form a choice; a lone freeform string is its own question.
    if (base !is ElicitationPropertySchema.StringProperty &&
        base !is ElicitationPropertySchema.ArrayProperty
    ) {
        return null
    }
    if (companion !is ElicitationPropertySchema.StringProperty) return null
    return candidate
}

private fun ElicitationPropertySchema.kind(options: List<AcpElicitationOption>): AcpElicitationFieldKind = when (this) {
    is ElicitationPropertySchema.ArrayProperty -> AcpElicitationFieldKind.MultiSelect
    is ElicitationPropertySchema.BooleanProperty -> AcpElicitationFieldKind.Boolean
    is ElicitationPropertySchema.IntegerProperty -> AcpElicitationFieldKind.Integer
    is ElicitationPropertySchema.NumberProperty -> AcpElicitationFieldKind.Number
    is ElicitationPropertySchema.StringProperty -> if (options.isEmpty()) {
        AcpElicitationFieldKind.Text
    } else {
        AcpElicitationFieldKind.Select
    }
}

private fun ElicitationPropertySchema.options(): List<AcpElicitationOption> = when (this) {
    is ElicitationPropertySchema.StringProperty -> when {
        !oneOf.isNullOrEmpty() -> oneOf.orEmpty().map { it.toOption() }
        !enumValues.isNullOrEmpty() -> enumValues.orEmpty().map { AcpElicitationOption(it, it) }
        else -> emptyList()
    }

    is ElicitationPropertySchema.ArrayProperty -> when (val items = items) {
        is MultiSelectItems.Titled -> items.items.options.map { it.toOption() }
        is MultiSelectItems.Untitled -> items.items.values.map { AcpElicitationOption(it, it) }
    }

    is ElicitationPropertySchema.BooleanProperty -> listOf(
        AcpElicitationOption("true", "Yes"),
        AcpElicitationOption("false", "No"),
    )

    else -> emptyList()
}

private fun EnumOption.toOption() = AcpElicitationOption(value, title)

private fun ElicitationPropertySchema.title(): String? = when (this) {
    is ElicitationPropertySchema.StringProperty -> title
    is ElicitationPropertySchema.ArrayProperty -> title
    is ElicitationPropertySchema.BooleanProperty -> title
    is ElicitationPropertySchema.IntegerProperty -> title
    is ElicitationPropertySchema.NumberProperty -> title
}

private fun ElicitationPropertySchema.description(): String? = when (this) {
    is ElicitationPropertySchema.StringProperty -> description
    is ElicitationPropertySchema.ArrayProperty -> description
    is ElicitationPropertySchema.BooleanProperty -> description
    is ElicitationPropertySchema.IntegerProperty -> description
    is ElicitationPropertySchema.NumberProperty -> description
}
