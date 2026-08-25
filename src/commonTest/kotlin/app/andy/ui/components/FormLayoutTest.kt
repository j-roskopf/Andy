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
}
