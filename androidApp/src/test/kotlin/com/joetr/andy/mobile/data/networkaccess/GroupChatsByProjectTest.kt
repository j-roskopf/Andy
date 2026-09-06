package com.joetr.andy.mobile.data.networkaccess

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChatsByProjectTest {
    @Test
    fun groupsAndOrdersByConfiguredProjects() {
        val projects = listOf(
            ProjectDto("a", "Alpha"),
            ProjectDto("b", "Beta"),
        )
        val chats = listOf(
            ChatDto(id = "1", title = "one", projectId = "b"),
            ChatDto(id = "2", title = "two", projectId = ""),
            ChatDto(id = "3", title = "three", projectId = "a"),
        )
        val groups = groupChatsByProject(projects, chats)
        assertEquals(listOf("a", "b", ""), groups.map { it.projectId })
        assertEquals("Alpha", groups[0].projectName)
        assertEquals("No project", groups[2].projectName)
    }
}
