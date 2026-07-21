package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
