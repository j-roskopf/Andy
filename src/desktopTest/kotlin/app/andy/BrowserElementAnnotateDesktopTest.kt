package app.andy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BrowserElementAnnotateDesktopTest {
    @Test
    fun parseCancelWhenTypeMissing() {
        val event = parseAnnotateEvent("""{"type":"cancel"}""", null)
        assertEquals(BrowserElementAnnotateEvent.Cancelled, event)
    }

    @Test
    fun parseSubmitReadsFieldsWithoutPersistingEmptyPng() {
        val event = parseAnnotateEvent(
            """
            {
              "type":"submit",
              "comment":"Make this primary",
              "tag":"center",
              "selector":"form > center",
              "url":"https://www.google.com/",
              "title":"Google",
              "width":517.0,
              "height":58,
              "color":"#e8e8e8",
              "font":"14px Roboto",
              "text":"Google Search"
            }
            """.trimIndent(),
            ByteArray(0),
        )
        val submitted = assertIs<BrowserElementAnnotateEvent.Submitted>(event)
        assertEquals("Make this primary", submitted.annotation.comment)
        assertEquals("center", submitted.annotation.tag)
        assertEquals(517, submitted.annotation.width)
        assertEquals(58, submitted.annotation.height)
        assertNull(submitted.annotation.imagePath)
    }
}
