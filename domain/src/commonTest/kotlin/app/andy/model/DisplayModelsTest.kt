package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayModelsTest {
    @Test
    fun mirrorTargetRoundTripsDisplayId() {
        assertEquals("SERIAL123", MirrorTarget.id("SERIAL123", 0))
        assertEquals("SERIAL123@display=2", MirrorTarget.id("SERIAL123", 2))
        assertEquals("SERIAL123", MirrorTarget.serial("SERIAL123@display=2"))
        assertEquals(2, MirrorTarget.displayId("SERIAL123@display=2"))
        assertEquals(0, MirrorTarget.displayId("SERIAL123"))
        assertTrue(MirrorTarget.isSecondaryDisplay("SERIAL123@display=2"))
        assertFalse(MirrorTarget.isSecondaryDisplay("SERIAL123"))
    }

    @Test
    fun parsesDefaultAndSecondaryDisplays() {
        val output = """
            Display: mDisplayId=0
              init=1080x2400 420dpi cur=1080x2400 app=1080x2368
              mDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1080 x 2400}
            Display: mDisplayId=1
              init=100x100 1dpi cur=100x100 app=100x100
            Display: mDisplayId=2
              init=1080x1920 320dpi cur=1080x1920 app=1080x1920
              mDisplayInfo=DisplayInfo{"Secondary Screen", displayId 2, real 1080 x 1920}
        """.trimIndent()

        val displays = parseAndroidDisplays(output)

        assertEquals(listOf(0, 2), displays.map { it.id })
        assertEquals("Built-in Screen", displays[0].name)
        assertEquals(1080 to 2400, displays[0].width to displays[0].height)
        assertEquals("Secondary Screen", displays[1].name)
        assertEquals(1080 to 1920, displays[1].width to displays[1].height)
    }

    @Test
    fun parsesDisplayCurrentSizeForSecondaryPanel() {
        val output = """
            Display: mDisplayId=0
              cur=1080x2400
            Display: mDisplayId=2
              cur=1920x1080
        """.trimIndent()

        assertEquals(1920 to 1080, parseAndroidDisplaySize(output, 2))
        assertEquals(null, parseAndroidDisplaySize(output, 7))
    }
}
