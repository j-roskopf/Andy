package com.joetr.andy.mobile.data.networkaccess

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAccessLoadingTest {
    @Test
    fun webSocketUrlsUseUpgradeSchemes() {
        assertEquals(
            "ws://100.72.168.32:8565/ws/attention?token=session",
            networkAccessWebSocketUrl(
                "http://100.72.168.32:8565",
                "/ws/attention?token=session",
            ),
        )
        assertEquals(
            "wss://andy.example.com/ws/chats/chat-id",
            networkAccessWebSocketUrl(
                "https://andy.example.com/",
                "ws/chats/chat-id",
            ),
        )
    }

    @Test
    fun projectAndChatListsStartTogether() = runTest {
        val projectsStarted = CompletableDeferred<Unit>()
        val chatsStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val loading = async {
            loadProjectGroupsConcurrently(
                loadProjects = {
                    projectsStarted.complete(Unit)
                    release.await()
                    listOf(ProjectDto("project", "Project"))
                },
                loadChats = {
                    chatsStarted.complete(Unit)
                    release.await()
                    listOf(ChatDto(id = "chat", projectId = "project"))
                },
            )
        }

        projectsStarted.await()
        chatsStarted.await()
        release.complete(Unit)

        assertEquals(listOf("chat"), loading.await().single().chats.map { it.id })
    }

    @Test
    fun chatDetailAndTranscriptSettingsStartTogether() = runTest {
        val detailStarted = CompletableDeferred<Unit>()
        val settingsStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val loading = async {
            loadChatInitialDataConcurrently(
                loadDetail = {
                    detailStarted.complete(Unit)
                    release.await()
                    ChatDetailDto(chat = ChatDto(id = "chat"))
                },
                loadSettings = {
                    settingsStarted.complete(Unit)
                    release.await()
                    TranscriptSettingsDto(showThinkingOnTimeline = true)
                },
            )
        }

        detailStarted.await()
        settingsStarted.await()
        release.complete(Unit)

        val result = loading.await()
        assertEquals("chat", result.detail.chat.id)
        assertEquals(true, result.settings?.showThinkingOnTimeline)
    }
}
