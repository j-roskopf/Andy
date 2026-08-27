package app.andy.domain

/**
 * Parses `wm user-rotation` stdout (`lock 1`, or a bare `0`–`3`).
 * Returns null for `free` — that is a rotation mode, not a quarter-turn value.
 */
fun parseWmUserRotation(stdout: String): Int? {
    val trimmed = stdout.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("free", ignoreCase = true)) return null
    Regex("""\block\s+([0-3])\b""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
        ?.toIntOrNull()
        ?.let { return it }
    Regex("""\b([0-3])\b""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return null
}

fun String.isEmulatorSerial(): Boolean = startsWith("emulator-")
