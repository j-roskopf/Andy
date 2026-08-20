package app.andy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewChatBackgroundUriTest {
    @Test
    fun blankBecomesNull() {
        assertNull(normalizeNewChatBackgroundUri(""))
        assertNull(normalizeNewChatBackgroundUri("   "))
    }

    @Test
    fun keepsRemoteUrls() {
        assertEquals(
            "https://example.com/bg.png",
            normalizeNewChatBackgroundUri("  https://example.com/bg.png  "),
        )
        assertEquals(
            "http://cdn.example/a.jpg",
            normalizeNewChatBackgroundUri("http://cdn.example/a.jpg"),
        )
        assertTrue(isRemoteNewChatBackgroundUri("HTTPS://example.com/x.webp"))
        assertFalse(isRemoteNewChatBackgroundUri("/tmp/bg.png"))
    }

    @Test
    fun stripsFileScheme() {
        assertEquals(
            "/Users/me/Pictures/bg.png",
            normalizeNewChatBackgroundUri("file:///Users/me/Pictures/bg.png"),
        )
        assertEquals(
            "/tmp/wallpaper.jpg",
            normalizeNewChatBackgroundUri("FILE:///tmp/wallpaper.jpg#frag"),
        )
    }

    @Test
    fun keepsLocalPaths() {
        assertEquals(
            "/Users/me/Pictures/bg.png",
            normalizeNewChatBackgroundUri("/Users/me/Pictures/bg.png"),
        )
        assertEquals(
            "C:\\Users\\me\\bg.png",
            normalizeNewChatBackgroundUri("C:\\Users\\me\\bg.png"),
        )
    }
}
