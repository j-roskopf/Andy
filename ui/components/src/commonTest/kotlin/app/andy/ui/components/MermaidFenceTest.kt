package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MermaidFenceTest {
    @Test
    fun recognizesMermaidFenceLanguages() {
        assertTrue(isMermaidFenceLanguage("mermaid"))
        assertTrue(isMermaidFenceLanguage("MERMAID"))
        assertTrue(isMermaidFenceLanguage("mmd"))
        assertTrue(isMermaidFenceLanguage("mermaid title=\"flow\""))
        assertTrue(isMermaidFenceLanguage("mermaid-js"))
        assertFalse(isMermaidFenceLanguage("kotlin"))
        assertFalse(isMermaidFenceLanguage("markdown"))
        assertFalse(isMermaidFenceLanguage(null))
        assertFalse(isMermaidFenceLanguage(""))
    }

    @Test
    fun readsViewBoxSizeFromSvg() {
        val svg = """<svg viewBox="0 0 240 120" xmlns="http://www.w3.org/2000/svg"></svg>"""
        assertEquals(240f to 120f, mermaidSvgIntrinsicSize(svg))
    }

    @Test
    fun readsWidthHeightWhenViewBoxMissing() {
        val svg = """<svg width="100" height="50"></svg>"""
        assertEquals(100f to 50f, mermaidSvgIntrinsicSize(svg))
    }
}
