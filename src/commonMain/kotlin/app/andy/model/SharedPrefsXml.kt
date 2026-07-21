package app.andy.model

/**
 * Parses and serializes Android SharedPreferences XML under shared_prefs.
 */
object SharedPrefsXml {
    fun parse(xml: String): List<PrefEntry> {
        val entries = mutableListOf<PrefEntry>()
        val tagRegex = Regex(
            """<(string|int|long|float|boolean|set)\s+name="([^"]*)"(?:\s+value="([^"]*)")?\s*(?:/>|>([\s\S]*?)</\1>)""",
        )
        for (match in tagRegex.findAll(xml)) {
            val typeName = match.groupValues[1]
            val key = decodeXml(match.groupValues[2])
            when (typeName) {
                "string" -> {
                    val body = match.groupValues[4]
                    entries += PrefEntry(key, PrefType.String, decodeXml(body))
                }
                "int" -> entries += PrefEntry(key, PrefType.Int, match.groupValues[3])
                "long" -> entries += PrefEntry(key, PrefType.Long, match.groupValues[3])
                "float" -> entries += PrefEntry(key, PrefType.Float, match.groupValues[3])
                "boolean" -> entries += PrefEntry(key, PrefType.Boolean, match.groupValues[3])
                "set" -> {
                    val body = match.groupValues[4]
                    val values = Regex("""<string>([\s\S]*?)</string>""")
                        .findAll(body)
                        .map { decodeXml(it.groupValues[1]) }
                        .toList()
                    entries += PrefEntry(key, PrefType.StringSet, values.joinToString("\n"))
                }
            }
        }
        return entries
    }

    fun serialize(entries: List<PrefEntry>): String {
        val body = buildString {
            entries.sortedBy { it.key }.forEach { entry ->
                when (entry.type) {
                    PrefType.String -> appendLine("""    <string name="${encodeXml(entry.key)}">${encodeXml(entry.value)}</string>""")
                    PrefType.Int -> appendLine("""    <int name="${encodeXml(entry.key)}" value="${encodeXml(entry.value)}" />""")
                    PrefType.Long -> appendLine("""    <long name="${encodeXml(entry.key)}" value="${encodeXml(entry.value)}" />""")
                    PrefType.Float -> appendLine("""    <float name="${encodeXml(entry.key)}" value="${encodeXml(entry.value)}" />""")
                    PrefType.Boolean -> appendLine("""    <boolean name="${encodeXml(entry.key)}" value="${encodeXml(entry.value)}" />""")
                    PrefType.StringSet -> {
                        appendLine("""    <set name="${encodeXml(entry.key)}">""")
                        val members = if (entry.value.isEmpty()) emptyList() else entry.value.split("\n")
                        members.forEach { member ->
                            appendLine("""        <string>${encodeXml(member)}</string>""")
                        }
                        appendLine("    </set>")
                    }
                }
            }
        }
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n$body</map>\n"
    }

    fun upsert(entries: List<PrefEntry>, entry: PrefEntry): List<PrefEntry> =
        entries.filterNot { it.key == entry.key } + entry

    fun delete(entries: List<PrefEntry>, key: String): List<PrefEntry> =
        entries.filterNot { it.key == key }

    fun valueValidationError(type: PrefType, rawValue: String): String? =
        if (coerceValue(type, rawValue) == null) {
            when (type) {
                PrefType.Boolean -> "Boolean value must be true or false"
                PrefType.Int -> "Int value must be a whole number"
                PrefType.Long -> "Long value must be a whole number"
                PrefType.Float -> "Float value must be a number"
                PrefType.String, PrefType.StringSet -> null
            }
        } else {
            null
        }

    fun coerceValue(type: PrefType, rawValue: String): String? {
        val value = rawValue.trim()
        return when (type) {
            PrefType.Boolean -> when (value.lowercase()) {
                "true", "false" -> value.lowercase()
                else -> null
            }
            PrefType.Int -> value.toIntOrNull()?.let { value }
            PrefType.Long -> value.toLongOrNull()?.let { value }
            PrefType.Float -> value.toFloatOrNull()?.let { value }
            PrefType.String, PrefType.StringSet -> value
        }
    }

    private fun encodeXml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun decodeXml(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
