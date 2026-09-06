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
    val unread: Boolean = false,
    val archived: Boolean = false,
    val createdAtMillis: Long = 0L,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    val resumable: Boolean = false,
    val errorMessage: String = "",
    val userInputRequest: UserInputRequestDto? = null,
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
data class RespondRequest(
    val requestId: String,
    val answers: Map<String, String>,
)

@Serializable
data class OkResponse(val ok: Boolean = true, val id: String = "")

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
        type == "error" || type == "permission-resolved"

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
