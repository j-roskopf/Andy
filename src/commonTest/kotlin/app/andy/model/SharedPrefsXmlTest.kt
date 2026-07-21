package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedPrefsXmlTest {
    @Test
    fun roundTripTypedEntries() {
        val original = listOf(
            PrefEntry("flag", PrefType.Boolean, "true"),
            PrefEntry("count", PrefType.Int, "3"),
            PrefEntry("big", PrefType.Long, "99"),
            PrefEntry("ratio", PrefType.Float, "1.5"),
            PrefEntry("name", PrefType.String, "hello & <world>"),
            PrefEntry("tags", PrefType.StringSet, "a\nb"),
        )
        val xml = SharedPrefsXml.serialize(original)
        val parsed = SharedPrefsXml.parse(xml)
        assertEquals(original.sortedBy { it.key }, parsed.sortedBy { it.key })
        assertTrue(xml.contains("<map>"))
    }

    @Test
    fun upsertAndDelete() {
        val base = listOf(PrefEntry("a", PrefType.String, "1"), PrefEntry("b", PrefType.Int, "2"))
        val updated = SharedPrefsXml.upsert(base, PrefEntry("a", PrefType.String, "9"))
        assertEquals("9", updated.first { it.key == "a" }.value)
        assertEquals(1, SharedPrefsXml.delete(updated, "b").size)
    }

    @Test
    fun coerceValueAcceptsTypedInputs() {
        assertEquals("true", SharedPrefsXml.coerceValue(PrefType.Boolean, " TRUE "))
        assertEquals("false", SharedPrefsXml.coerceValue(PrefType.Boolean, "False"))
        assertEquals("42", SharedPrefsXml.coerceValue(PrefType.Int, " 42 "))
        assertEquals("99", SharedPrefsXml.coerceValue(PrefType.Long, "99"))
        assertEquals("1.5", SharedPrefsXml.coerceValue(PrefType.Float, "1.5"))
        assertEquals("hello", SharedPrefsXml.coerceValue(PrefType.String, " hello "))
        assertEquals("a\nb", SharedPrefsXml.coerceValue(PrefType.StringSet, "a\nb"))
    }

    @Test
    fun coerceValueRejectsMismatchedTypes() {
        assertNull(SharedPrefsXml.coerceValue(PrefType.Boolean, "yes"))
        assertNull(SharedPrefsXml.coerceValue(PrefType.Boolean, "1"))
        assertNull(SharedPrefsXml.coerceValue(PrefType.Int, "true"))
        assertNull(SharedPrefsXml.coerceValue(PrefType.Int, "1.5"))
        assertNull(SharedPrefsXml.coerceValue(PrefType.Long, "nope"))
        assertNull(SharedPrefsXml.coerceValue(PrefType.Float, "abc"))
    }

    @Test
    fun valueValidationErrorMessages() {
        assertEquals(
            "Boolean value must be true or false",
            SharedPrefsXml.valueValidationError(PrefType.Boolean, "maybe"),
        )
        assertEquals(
            "Int value must be a whole number",
            SharedPrefsXml.valueValidationError(PrefType.Int, "true"),
        )
        assertNull(SharedPrefsXml.valueValidationError(PrefType.Boolean, "true"))
        assertNull(SharedPrefsXml.valueValidationError(PrefType.String, "anything"))
    }
}
