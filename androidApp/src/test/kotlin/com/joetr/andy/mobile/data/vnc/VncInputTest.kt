package com.joetr.andy.mobile.data.vnc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VncInputTest {
    @Test
    fun appendingOneCharacterSendsOnlyThatCharacter() {
        assertEquals(TextEdit(0, "a"), diffText("", "a"))
        assertEquals(TextEdit(0, "c"), diffText("ab", "abc"))
    }

    @Test
    fun deletingSendsBackspacesOnly() {
        assertEquals(TextEdit(1, ""), diffText("abc", "ab"))
        assertEquals(TextEdit(3, ""), diffText("abc", ""))
    }

    /** Gboard rewrites the whole word on autocorrect; length alone cannot describe that. */
    @Test
    fun autocorrectReplacementIsExpressedAsBackspacesPlusInsert() {
        assertEquals(TextEdit(2, "he"), diffText("teh", "the"))
        // Shared prefix "hi t" survives, so only "eh" is rewritten as "here".
        assertEquals(TextEdit(2, "here"), diffText("hi teh", "hi there"))
    }

    @Test
    fun midStringInsertKeepsTheSuffix() {
        // "abd" -> "abcd": prefix "ab", suffix "d", so one insert and no deletions.
        assertEquals(TextEdit(0, "c"), diffText("abd", "abcd"))
    }

    @Test
    fun identicalTextIsANoOp() {
        assertTrue(diffText("same", "same").isEmpty)
    }

    @Test
    fun gestureTypingAWholeWordSendsTheWord() {
        assertEquals(TextEdit(0, "hello"), diffText("", "hello"))
    }

    @Test
    fun latinCharactersMapToTheirOwnKeysym() {
        assertEquals('a'.code, charToKeysym('a'))
        assertEquals('Z'.code, charToKeysym('Z'))
        assertEquals(Keysym.Return, charToKeysym('\n'))
        assertEquals(Keysym.Tab, charToKeysym('\t'))
    }

    @Test
    fun nonLatinCharactersUseTheUnicodeKeysymRange() {
        assertEquals(0x01000000 + 0x2713, charToKeysym('✓'))
    }

    @Test
    fun shiftedCharactersAreFlagged() {
        assertTrue(charRequiresShift('A'))
        assertTrue(charRequiresShift('!'))
        assertFalse(charRequiresShift('a'))
        assertFalse(charRequiresShift('1'))
    }
}
