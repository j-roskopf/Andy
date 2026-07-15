package app.andy.desktop.service.agents

import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

private val ANDY_USER_INPUT_BLOCK = Regex(
    """<andy_user_input>\s*(\{[\s\S]*?})\s*</andy_user_input>""",
    setOf(RegexOption.IGNORE_CASE),
)

internal data class ParsedAgentUserInput(
    val request: AgentUserInputRequest,
    val visibleText: String,
)

/**
 * Parses the provider-neutral decision checkpoint used by portable skills. The
 * wire format is deliberately strict: invalid blocks remain ordinary prose, so
 * a malformed model response can never leave a task in an unanswerable state.
 */
internal fun parseAgentUserInput(text: String): ParsedAgentUserInput? {
    val match = ANDY_USER_INPUT_BLOCK.find(text) ?: return null
    val root = parseJsonObject(match.groupValues[1]) ?: return null
    val questions = root["questions"] as? JsonArray ?: return null
    if (questions.size !in 1..3) return null

    val parsedQuestions = questions.mapNotNull(::parseQuestion)
    if (parsedQuestions.size != questions.size || parsedQuestions.map { it.id }.distinct().size != parsedQuestions.size) return null

    return ParsedAgentUserInput(
        request = AgentUserInputRequest(
            id = UUID.randomUUID().toString(),
            questions = parsedQuestions,
        ),
        visibleText = text.removeRange(match.range).trim(),
    )
}

internal fun AgentUserInputRequest.responseForAgent(answers: Map<String, String>): String = buildString {
    append("Here are the user's answers to the decision checkpoint:\n")
    questions.forEach { question ->
        append("- ").append(question.question).append(": ")
            .append(answers.getValue(question.id).trim()).append('\n')
    }
    append("Continue from these decisions. Do not reopen a resolved question unless the answer creates a concrete contradiction.")
}.trimEnd()

private fun parseQuestion(element: kotlinx.serialization.json.JsonElement): AgentUserInputQuestion? {
    val question = element as? JsonObject ?: return null
    val id = question.stringOrNull("id")?.trim()?.takeIf(::isValidId) ?: return null
    val text = question.stringOrNull("question")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val options = (question["options"] as? JsonArray)?.mapNotNull(::parseOption).orEmpty()
    if (options.size !in 2..3 || options.map { it.label.lowercase() }.distinct().size != options.size) return null
    return AgentUserInputQuestion(
        id = id,
        header = question.stringOrNull("header")?.trim().orEmpty(),
        question = text,
        options = options,
    )
}

private fun parseOption(element: kotlinx.serialization.json.JsonElement): AgentUserInputOption? {
    val option = element as? JsonObject ?: return null
    val label = option.stringOrNull("label")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return AgentUserInputOption(label, option.stringOrNull("description")?.trim().orEmpty())
}

private fun isValidId(value: String): Boolean = value.matches(Regex("[a-z][a-z0-9_]{0,63}"))
