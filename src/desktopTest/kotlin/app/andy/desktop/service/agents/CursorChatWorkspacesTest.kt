package app.andy.desktop.service.agents

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CursorChatWorkspacesTest {
    @Test
    fun findsChatByIdAcrossHashedWorkspaceBuckets() {
        val home = createTempDirectory("andy-cursor-chats").toFile()
        val chats = home.resolve(".cursor/chats")
        val original = chats.resolve("abc123/chat-from-home")
        original.mkdirs()
        original.resolve("meta.json").writeText(
            """{"cwd":"/Users/joer","title":"Plano Weather","hasConversation":true,"updatedAtMs":200}""",
        )
        original.resolve("prompt_history.json").writeText(
            """["what is this chat id","what is the weather in plano, tx"]""",
        )
        val other = chats.resolve("def456/chat-from-home")
        other.mkdirs()
        other.resolve("meta.json").writeText(
            """{"cwd":"/Users/joer/Code/Andy/Andy","title":"Previous Message","hasConversation":true,"updatedAtMs":999}""",
        )
        other.resolve("prompt_history.json").writeText(
            """["what was my previous message"]""",
        )

        val found = CursorChatWorkspaces.find("chat-from-home", home)
        assertEquals("/Users/joer", found?.cwd)
        assertEquals("Plano Weather", found?.title)
        assertTrue(found?.hasConversation == true)
    }

    @Test
    fun returnsNullWhenChatIdIsUnknown() {
        val home = createTempDirectory("andy-cursor-chats-missing").toFile()
        home.resolve(".cursor/chats").mkdirs()
        assertNull(CursorChatWorkspaces.find("missing-id", home))
    }
}
