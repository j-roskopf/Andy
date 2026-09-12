package com.joetr.andy.mobile.data.networkaccess

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal suspend fun loadProjectGroupsConcurrently(
    loadProjects: suspend () -> List<ProjectDto>,
    loadChats: suspend () -> List<ChatDto>,
): List<ProjectGroup> = coroutineScope {
    val projects = async { loadProjects() }
    val chats = async { loadChats() }
    groupChatsByProject(projects.await(), chats.await())
}

internal data class ChatInitialData(
    val detail: ChatDetailDto,
    val settings: TranscriptSettingsDto?,
)

internal suspend fun loadChatInitialDataConcurrently(
    loadDetail: suspend () -> ChatDetailDto,
    loadSettings: suspend () -> TranscriptSettingsDto?,
): ChatInitialData = coroutineScope {
    val detail = async { loadDetail() }
    val settings = async { loadSettings() }
    ChatInitialData(detail = detail.await(), settings = settings.await())
}
