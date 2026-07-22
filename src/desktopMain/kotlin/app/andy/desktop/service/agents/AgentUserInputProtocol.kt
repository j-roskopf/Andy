package app.andy.desktop.service.agents

import app.andy.model.AgentUserInputOption
import app.andy.model.AgentUserInputQuestion
import app.andy.model.AgentUserInputRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

private val ANDY_USER_INPUT_OPEN = Regex("<andy_user_input>", RegexOption.IGNORE_CASE)
private val ANDY_USER_INPUT_CLOSE = Regex("</andy_user(?:_input)?>", RegexOption.IGNORE_CASE)

internal data class ParsedAgentUserInput(
    val request: AgentUserInputRequest,
    val visibleText: String,
)

/**
 * Parses the provider-neutral decision checkpoint used by portable skills. The
 * wire format is deliberately strict on JSON shape, but tolerant of common
 * closing-tag mistakes so a malformed model response can never leave a task in
 * an unanswerable state.
 */
internal fun parseAgentUserInput(text: String): ParsedAgentUserInput? {
    val open = ANDY_USER_INPUT_OPEN.find(text) ?: return null
    var jsonStart = open.range.last + 1
    while (jsonStart < text.length && text[jsonStart].isWhitespace()) jsonStart++
    val jsonEnd = findJsonObjectEnd(text, jsonStart) ?: return null
    val json = text.substring(jsonStart, jsonEnd).trim()
    val remainder = text.substring(jsonEnd)
    val close = ANDY_USER_INPUT_CLOSE.find(remainder)
    val blockEnd = close?.let { jsonEnd + it.range.last } ?: (jsonEnd - 1)
    return parseAgentUserInputPayload(text, open.range.first..blockEnd, json)
}

internal fun parseAgentUserInputFromSources(vararg texts: String?): ParsedAgentUserInput? =
    texts.firstNotNullOfOrNull { source ->
        source?.takeIf { it.isNotBlank() }?.let(::parseAgentUserInput)
    }

/** Hide checkpoint wire format when a provider streamed it into the transcript. */
internal fun stripAgentUserInputMarkup(text: String): String {
    val parsed = parseAgentUserInput(text) ?: return ANDY_USER_INPUT_OPEN.replace(text, "").let { stripped ->
        ANDY_USER_INPUT_CLOSE.replace(stripped, "").trim()
    }
    return parsed.visibleText
}

/** Plan text must not be a decision checkpoint the user still needs to answer. */
internal fun agentPlanTextCandidate(text: String?): String? {
    val trimmed = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
    parseAgentUserInput(trimmed)?.let { parsed ->
        return parsed.visibleText.takeIf { it.isNotBlank() }
    }
    if (trimmed.contains("<andy_user_input", ignoreCase = true)) return null
    return trimmed
}

internal fun containsPartialAgentUserInputMarkup(text: String): Boolean =
    text.contains("<andy_user_input", ignoreCase = true) && parseAgentUserInput(text) == null

internal fun AgentUserInputRequest.responseForAgent(answers: Map<String, String>): String = buildString {
    append("Here are the user's answers to the decision checkpoint:\n")
    questions.forEach { question ->
        append("- ").append(question.question).append(": ")
            .append(answers.getValue(question.id).trim()).append('\n')
    }
    append("Continue from these decisions. Do not reopen a resolved question unless the answer creates a concrete contradiction.")
}.trimEnd()

private fun parseAgentUserInputPayload(text: String, range: IntRange, json: String): ParsedAgentUserInput? {
    val root = parseJsonObject(json) ?: return null
    val questions = root["questions"] as? JsonArray ?: return null
    if (questions.size !in 1..3) return null

    val parsedQuestions = questions.mapNotNull(::parseQuestion)
    if (parsedQuestions.size != questions.size || parsedQuestions.map { it.id }.distinct().size != parsedQuestions.size) return null

    return ParsedAgentUserInput(
        request = AgentUserInputRequest(
            id = UUID.randomUUID().toString(),
            questions = parsedQuestions,
        ),
        visibleText = text.removeRange(range).trim(),
    )
}

private fun findJsonObjectEnd(text: String, start: Int): Int? {
    if (text.getOrNull(start) != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        when (val char = text[index]) {
            '\\' -> if (inString) escaped = true
            '"' -> if (!escaped) inString = !inString else escaped = false
            '{' -> if (!inString) depth++
            '}' -> if (!inString) {
                depth--
                if (depth == 0) return index + 1
            }
            else -> escaped = false
        }
    }
    return null
}

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
