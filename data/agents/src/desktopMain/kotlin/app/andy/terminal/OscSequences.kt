package app.andy.terminal

/** Latest OSC 0/2 title payload from a raw ANSI stream (BEL or ST terminated). */
internal fun extractLatestOscTitle(raw: String): String =
    extractLatestOscPayload(raw, codes = setOf("0", "2"))

/**
 * Latest ConEmu/OSC progress payload (`9;4;…` → stored as `4;…` to match Herdr manifests).
 */
internal fun extractLatestOscProgress(raw: String): String {
    val payload = extractLatestOscPayload(raw, codes = setOf("9"))
    return if (payload.startsWith("4;")) payload else ""
}

/** ESC ] Ps ; Pt (BEL | ESC \). Hoisted so it compiles once, not on every scan. */
internal val OSC_PATTERN = Regex("""\u001B\](\d+);([^\u0007\u001B]*)(?:\u0007|\u001B\\)""")

internal fun extractLatestOscPayload(raw: String, codes: Set<String>): String {
    if (raw.isEmpty()) return ""
    var latest = ""
    for (match in OSC_PATTERN.findAll(raw)) {
        if (match.groupValues[1] in codes) {
            latest = match.groupValues[2]
        }
    }
    return latest
}
