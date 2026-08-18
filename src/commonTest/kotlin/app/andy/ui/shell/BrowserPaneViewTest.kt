package app.andy.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserPaneViewTest {
    @Test
    fun normalizeBrowserUrlKeepsHttpLocalhost() {
        assertEquals("http://localhost:8080", normalizeBrowserUrl("http://localhost:8080"))
        assertEquals("http://localhost:8080", normalizeBrowserUrl("localhost:8080"))
        assertEquals("http://127.0.0.1:5173", normalizeBrowserUrl("127.0.0.1:5173"))
    }

    @Test
    fun normalizeBrowserUrlUsesHttpsForPublicHosts() {
        assertEquals("https://example.com", normalizeBrowserUrl("example.com"))
        assertEquals("https://example.com/path", normalizeBrowserUrl("https://example.com/path"))
    }
}
