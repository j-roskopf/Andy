package app.andy.terminal

import io.github.ketraterm.core.api.TerminalBuffer

private val SpinnerStatusLine = Regex("""[⠀-⣿].*\b(Working|Running|Grepping|Reading|Loading|Thinking)\b""")
private val TokenStatusLine = Regex("""\b(Working|Running|Grepping|Reading|Loading|Thinking)\b.*\btokens\b""", RegexOption.IGNORE_CASE)
private val SpinnerTokenLine = Regex("""[⠀-⣿].*tokens""", RegexOption.IGNORE_CASE)
private val AutoPercentLine = Regex("""\bAuto\s*·\s*\d""")
private val PathStatusLine = Regex("""~/.*·\s*\w""")
private val ToolProgressLine = Regex("""^(Read(ing)?|Grep(ped|ping)?)\b""")
private val EllipsisPathLine = Regex("""\.\.\.""")
private val TruncatedReviewLine = Regex("""truncated.*ctrl\+r to review""", RegexOption.IGNORE_CASE)
private val ShellEchoLine = Regex("""^".+"\s+2>&1""")
private val EarlierItemsHiddenLine = Regex("""…\s+\d+\s+earlier items hidden""")
/** ASCII or Unicode horizontal rules from Ink/TUI chrome (incl. `─── ───` session marks). */
private val RuleLine = Regex("""^[─━═\-_|▕▏\s]{3,}$""")
/** Antigravity/Cursor slash-command palette rows (`/agents  … description`). */
private val SlashCommandMenuLine = Regex("""^\s*>?\s*/[a-z0-9][a-z0-9_-]*\s{2,}\S""", RegexOption.IGNORE_CASE)
private val SlashMenuChromeLine = Regex(
    """↑/↓|esc to cancel|↓\s*\d+\s+more|tab complete|enter select""",
    RegexOption.IGNORE_CASE,
)
private val ProviderStatusFooter = Regex("""·\s*(high|medium|low|auto)\s*$""", RegexOption.IGNORE_CASE)
private val BannerEmailLine = Regex("""^\S+@\S+\.\S+(\s+\([^)]+\))?$""")
private val BannerModelLine = Regex(
    """^(Gemini|Claude|GPT|Sonnet|Opus|Flash)\b.*\((High|Medium|Low|Auto)\)\s*$""",
    RegexOption.IGNORE_CASE,
)

/** Drop TUI chrome and spinner redraw lines that make replay unreadable. */
internal fun isScrollbackNoiseLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return true
    val lower = trimmed.lowercase()
    if (Regex("""^:+\s*(Working|Running|Thinking|Grepping|Reading|Loading)\b""").containsMatchIn(trimmed)) return true
    if (SpinnerStatusLine.containsMatchIn(trimmed)) return true
    if (TokenStatusLine.containsMatchIn(trimmed)) return true
    if (SpinnerTokenLine.containsMatchIn(trimmed)) return true
    if (Regex("""\b(Working|Running|Loading|Thinking)\b""").containsMatchIn(trimmed) &&
        trimmed.length < 80 &&
        !trimmed.contains('?')
    ) {
        return true
    }
    if (trimmed == "→ Add a follow-up" || lower.contains("ctrl+c to stop")) return true
    if (lower.contains("run everything")) return true
    if (Regex("""^\d+\s+tasks?$""").containsMatchIn(trimmed)) return true
    if (AutoPercentLine.containsMatchIn(trimmed)) return true
    if (PathStatusLine.containsMatchIn(trimmed)) return true
    if (Regex("""^\$\s+""").containsMatchIn(trimmed)) return true
    if (Regex("""^\d+ms$""").containsMatchIn(trimmed)) return true
    if (Regex("""exit\s+\d+""").containsMatchIn(lower) && trimmed.length < 40) return true
    return false
}

/** Extra lines to omit when presenting saved history as readable text. */
internal fun isScrollbackDisplayNoise(line: String): Boolean {
    if (isScrollbackNoiseLine(line)) return true
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return true
    val lower = trimmed.lowercase()
    if (lower.startsWith("tip:")) return true
    if (lower.startsWith("cursor agent")) return true
    if (lower.startsWith("antigravity cli")) return true
    if (lower.matches(Regex("""v\d{4}\.\d{2}\.\d{2}.*"""))) return true
    if (ToolProgressLine.containsMatchIn(trimmed)) return true
    if (Regex("""\bEdited\b.*\+\d+""").containsMatchIn(trimmed)) return true
    if (EarlierItemsHiddenLine.containsMatchIn(trimmed)) return true
    if (TruncatedReviewLine.containsMatchIn(trimmed)) return true
    if (ShellEchoLine.containsMatchIn(trimmed)) return true
    if (trimmed == "→" || trimmed == "Auto ·") return true
    if (trimmed == ">" || trimmed == "> /" || trimmed == "/") return true
    if (lower.startsWith("~/cod") || (lower.startsWith("~/") && !lower.contains(' ') && trimmed.length < 80)) {
        return true
    }
    if (lower.startsWith("run this command?")) return true
    if (lower.startsWith("not in allowlist:")) return true
    if (lower.startsWith("add shell(")) return true
    if (lower.startsWith("skip & tell the agent")) return true
    if (RuleLine.matches(trimmed)) return true
    if (SlashCommandMenuLine.containsMatchIn(trimmed)) return true
    if (SlashMenuChromeLine.containsMatchIn(trimmed)) return true
    if (ProviderStatusFooter.containsMatchIn(trimmed) && trimmed.length < 80) return true
    if (BannerEmailLine.matches(trimmed)) return true
    if (BannerModelLine.matches(trimmed)) return true
    if (lower.contains("waiting for approval")) return true
    if (lower.startsWith("→ run (once)")) return true
    return false
}

internal fun isScrollbackDiffLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("▎") ||
        (trimmed.startsWith("|") && trimmed.length > 1 && !trimmed.startsWith("||"))
}

internal fun scrollbackDiffContent(line: String): String =
    line.trim().removePrefix("▎").trimStart('|').trimStart()

/**
 * Turn captured terminal rows into readable paragraphs and intact diff blocks.
 * Terminal replay scatters these lines when widths differ, so history uses this for display.
 */
internal fun formatScrollbackForDisplay(raw: String): String {
    val output = mutableListOf<String>()
    val diffLines = mutableListOf<String>()

    fun flushDiff() {
        if (diffLines.isEmpty()) return
        output += diffLines.joinToString("\n")
        diffLines.clear()
    }

    for (line in raw.lines()) {
        if (isScrollbackDisplayNoise(line)) continue
        if (isScrollbackDiffLine(line)) {
            diffLines += scrollbackDiffContent(line)
            continue
        }
        flushDiff()
        val trimmed = line.trim()
        if (trimmed.isNotEmpty()) {
            output += trimmed
        }
    }
    flushDiff()
    return output.joinToString("\n\n").trim()
}

internal fun extractReadableLines(buffer: TerminalBuffer): List<String> {
    val total = buffer.historySize + buffer.height
    if (total <= 0) return emptyList()
    return buildList {
        for (row in 0 until total) {
            val line = buffer.getLineAsString(row).trimEnd()
            if (!isScrollbackNoiseLine(line) && !isScrollbackDisplayNoise(line)) add(line)
        }
    }
}

/** Append only meaningful lines we have not captured yet (conversation before scroll-out). */
internal fun captureNewReadableLines(
    buffer: TerminalBuffer,
    seenKeys: MutableSet<String>,
): List<String> = buildList {
    for (line in extractReadableLines(buffer)) {
        val key = line.trim()
        if (key.isEmpty() || key in seenKeys) continue
        seenKeys += key
        add(line)
    }
}

internal fun joinReadableLines(lines: List<String>): String = lines.joinToString("\n").trimEnd()
