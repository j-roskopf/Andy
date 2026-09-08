package com.joetr.andy.mobile.data.networkaccess

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    val sessionToken: String,
    val expiresAtMillis: Long = 0L,
    val scope: String = "full",
)

@Serializable
data class ApiError(val error: String? = null)

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
)

@Serializable
data class ChatDto(
    val id: String,
    val title: String = "",
    val prompt: String = "",
    val agent: String = "",
    val lane: String = "",
    val status: String = "",
    val projectId: String = "",
    val autonomy: String = "Standard",
    val planMode: Boolean = false,
    val workflowStage: String = "",
    val unread: Boolean = false,
    val archived: Boolean = false,
    val createdAtMillis: Long = 0L,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    val resumable: Boolean = false,
    val errorMessage: String = "",
    val providerAuthRecovery: ProviderAuthRecoveryDto? = null,
    val userInputRequest: UserInputRequestDto? = null,
)

@Serializable
data class ProviderAuthRecoveryDto(
    val needed: Boolean = true,
    val agent: String = "",
    val command: String = "",
    val instructions: String = "",
    val remoteInstructions: String = "",
)

@Serializable
data class ProviderLoginResponse(
    val ok: Boolean = false,
    val agent: String = "",
    val command: String = "",
    val opened: Boolean = false,
    val instructions: String = "",
    val remoteInstructions: String = "",
    val message: String = "",
)

/** Matches desktop [app.andy.ui.agents.isAwaitingPlanConfirmation] for Network Access chats. */
fun ChatDto.awaitingPlanConfirmation(hasPendingPlanEntries: Boolean = false): Boolean =
    status.equals("Done", ignoreCase = true) &&
        userInputRequest == null &&
        (planMode || hasPendingPlanEntries)

fun ChatDto.showImplementPlan(hasPendingPlanEntries: Boolean = false): Boolean =
    awaitingPlanConfirmation(hasPendingPlanEntries) &&
        !workflowStage.equals("Spec", ignoreCase = true)

fun ChatDto.displayStatusLabel(hasPendingPlanEntries: Boolean = false): String = when {
    awaitingPlanConfirmation(hasPendingPlanEntries) -> "plan ready"
    status.isNotBlank() -> status
    else -> ""
}

/** True when the chat list should surface a status marker (blocked / error / plan ready). */
fun ChatDto.hasListAttentionMarker(): Boolean =
    userInputRequest != null ||
        status.equals("Blocked", ignoreCase = true) ||
        status.equals("Error", ignoreCase = true) ||
        awaitingPlanConfirmation()

@Serializable
data class PlanEntryDto(
    val content: String = "",
    val status: String = "pending",
)

@Serializable
data class UserInputRequestDto(
    val id: String,
    val origin: String = "",
    val questions: List<UserInputQuestionDto> = emptyList(),
)

@Serializable
data class UserInputQuestionDto(
    val id: String,
    val header: String = "",
    val question: String = "",
    val options: List<UserInputOptionDto> = emptyList(),
)

@Serializable
data class UserInputOptionDto(
    val label: String = "",
    val description: String = "",
)

@Serializable
data class ChatDetailDto(
    val chat: ChatDto,
    val events: List<ChatEventDto> = emptyList(),
)

@Serializable
data class ChatEventDto(
    val type: String = "raw",
    val text: String = "",
    val atMillis: Long = 0L,
    val stream: Boolean = false,
    val toolName: String = "",
    val summary: String = "",
    val detail: String = "",
    val allowed: Boolean? = null,
    val optionId: String = "",
    val note: String = "",
    val success: Boolean? = null,
    val finalText: String = "",
    /** Plan-update markdown when [type] is `plan`. */
    val markdown: String = "",
    /** Plan checklist entries when [type] is `plan`. */
    val entries: List<PlanEntryDto> = emptyList(),
    /** Catch-all so unknown event fields don't break decoding. */
    val sessionId: String = "",
    val model: String = "",
)

@Serializable
data class AgentDto(
    val id: String,
    val label: String = "",
    val cliName: String = "",
    val webChat: Boolean = false,
)

@Serializable
data class ModelsResponse(
    val agent: String = "",
    val models: List<ModelOptionDto> = emptyList(),
    val defaultModel: String? = null,
    val defaultRuntime: String? = null,
    val requiresModel: Boolean = false,
)

@Serializable
data class ModelOptionDto(
    val id: String,
    val label: String = "",
)

@Serializable
data class SlashCommandDto(
    val name: String = "",
    val description: String = "",
)

@Serializable
data class StartChatRequest(
    val prompt: String,
    val agent: String,
    val autonomy: String = "Standard",
    val projectId: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val title: String? = null,
)

@Serializable
data class StartChatResponse(
    val id: String? = null,
    val status: String = "",
    val error: String? = null,
)

@Serializable
data class ReplyRequest(val message: String)

@Serializable
data class UpdatePlanModeRequest(val planMode: Boolean)

@Serializable
data class RespondRequest(
    val requestId: String,
    val answers: Map<String, String>,
)

@Serializable
data class OkResponse(val ok: Boolean = true, val id: String = "")

@Serializable
data class TranscriptSettingsDto(
    val showThinkingOnTimeline: Boolean = false,
    val autoExpandToolSections: Boolean = false,
    val collapseActivityBetweenMessages: Boolean = false,
)

data class ProjectGroup(
    val projectId: String,
    val projectName: String,
    val chats: List<ChatDto>,
)

fun groupChatsByProject(
    projects: List<ProjectDto>,
    chats: List<ChatDto>,
): List<ProjectGroup> {
    val names = projects.associate { it.id to it.name }
    val grouped = linkedMapOf<String, MutableList<ChatDto>>()
    for (chat in chats) {
        val key = chat.projectId.ifBlank { "" }
        grouped.getOrPut(key) { mutableListOf() }.add(chat)
    }
    val orderedKeys = buildList {
        projects.forEach { add(it.id) }
        grouped.keys.filter { it.isNotEmpty() && it !in names }.sorted().forEach { add(it) }
        if (grouped.containsKey("")) add("")
    }.distinct()
    return orderedKeys.mapNotNull { key ->
        val list = grouped[key] ?: return@mapNotNull null
        ProjectGroup(
            projectId = key,
            projectName = when {
                key.isEmpty() -> "No project"
                else -> names[key] ?: key
            },
            chats = list,
        )
    }
}

fun ChatEventDto.isVisibleTranscript(): Boolean =
    type == "user" || type == "assistant" || type == "thinking" ||
        type == "tool" || type == "tool-result" ||
        type == "error" || type == "permission-resolved" || type == "plan"

/**
 * Mirrors [app.andy.model.latestPlanHasPendingEntries] for Network Access wire events.
 * Cursor Create Plan can end_turn with pending rows while Andy [ChatDto.planMode] stays off.
 */
fun List<ChatEventDto>.latestPlanHasPendingEntries(): Boolean {
    val planIndex = indexOfLast { it.type == "plan" }
    if (planIndex < 0) return false
    if (withIndex().any { (index, event) -> index > planIndex && event.type == "user" }) {
        return false
    }
    val plan = this[planIndex]
    if (plan.entries.isEmpty()) {
        return plan.markdown.isNotBlank()
    }
    return plan.entries.any { entry ->
        when (entry.status.trim().lowercase()) {
            "completed", "complete", "done", "cancelled", "canceled", "file" -> false
            else -> true
        }
    }
}

fun List<ChatEventDto>.coalesceStreams(): List<ChatEventDto> {
    if (isEmpty()) return this
    val out = ArrayList<ChatEventDto>(size)
    for (event in this) {
        val last = out.lastOrNull()
        if (
            last != null &&
            event.stream &&
            last.type == event.type &&
            (event.type == "assistant" || event.type == "thinking")
        ) {
            out[out.lastIndex] = last.copy(text = last.text + event.text, stream = true)
        } else {
            out.add(event)
        }
    }
    return out
}
