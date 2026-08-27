package app.andy.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownCheckboxToggleTest {
    @Test
    fun togglesUncheckedToChecked() {
        val source = "- [ ] ship it\n- [x] done"
        val start = source.indexOf("[ ]")
        val end = start + 3
        assertEquals("- [x] ship it\n- [x] done", toggleMarkdownCheckbox(source, start, end))
    }

    @Test
    fun togglesCheckedToUnchecked() {
        val source = "1. [x] foo\n2. [X] bar"
        val start = source.indexOf("[x]")
        val end = start + 3
        assertEquals("1. [ ] foo\n2. [X] bar", toggleMarkdownCheckbox(source, start, end))
    }

    @Test
    fun togglesUppercaseCheckedMarker() {
        val source = "- [X] note"
        val start = source.indexOf("[X]")
        assertEquals("- [ ] note", toggleMarkdownCheckbox(source, start, start + 3))
    }

    @Test
    fun rejectsInvalidRanges() {
        assertNull(toggleMarkdownCheckbox("hello", 0, 5))
        assertNull(toggleMarkdownCheckbox("- [ ] x", -1, 2))
        assertNull(toggleMarkdownCheckbox("- [ ] x", 2, 2))
    }
}
