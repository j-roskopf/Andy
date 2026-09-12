package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class FormLayoutTest {
    @Test
    fun directionDefaultsToVertical() {
        assertEquals(FormLayoutDirection.Vertical, FormLayoutDirection.Vertical)
        assertEquals(3, FormLayoutDirection.entries.size)
    }
}

class CommandPaletteItemTest {
    @Test
    fun itemsCarryGroupLabels() {
        val item = CommandPaletteItem(
            id = "project:1",
            label = "Andy",
            group = "Projects",
            supporting = "/Users/joer/Code/Andy",
        )
        assertEquals("Projects", item.group)
        assertEquals("Andy", item.label)
    }

    @Test
    fun filterMatchesLabelAndKeywords() {
        val items = listOf(
            CommandPaletteItem("chat:1", "OAuth", "Chats", keywords = listOf("refresh token")),
            CommandPaletteItem("chat:2", "Unrelated", "Chats"),
        )
        val filtered = filterCommandPaletteItems(items, "refresh")
        assertEquals(listOf("chat:1"), filtered.map { it.id })
    }

    @Test
    fun excludedChatIdsFromSyncResults() {
        val sync = listOf(
            CommandPaletteItem("project:p", "Proj", "Projects"),
            CommandPaletteItem("chat:abc", "Title", "Chats"),
        )
        assertEquals(setOf("abc"), commandPaletteExcludedChatIds(sync))
    }
}
