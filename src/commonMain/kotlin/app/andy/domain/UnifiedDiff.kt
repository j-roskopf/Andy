package app.andy.domain

import app.andy.model.AgentFileDiff
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind

private val HunkHeader = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s@@""")
private val HunkMarker = Regex("""(?m)^@@ .+ @@""")

/** True when [text] looks like a unified diff (---/+++ headers plus at least one @@ hunk). */
fun looksLikeUnifiedDiff(text: String): Boolean {
    if (!HunkMarker.containsMatchIn(text)) return false
    var sawOld = false
    var sawNew = false
    text.lineSequence().forEach { line ->
        if (line.startsWith("--- ")) sawOld = true
        if (line.startsWith("+++ ")) sawNew = true
    }
    return sawOld && sawNew
}

/** Returns the patch portion while leaving any leading command diagnostics outside it. */
fun extractUnifiedDiffText(text: String): String? {
    val start = Regex("""(?m)^(?:diff --git |--- )""").find(text)?.range?.first ?: return null
    val suffix = text.substring(start)
    if (!looksLikeUnifiedDiff(suffix)) return null
    val lines = suffix.split('\n')
    var endLine = -1
    var sawHunk = false
    var oldLinesRemaining = 0
    var newLinesRemaining = 0
    for ((index, line) in lines.withIndex()) {
        val hunk = HunkHeader.find(line)
        if (hunk != null) {
            sawHunk = true
            oldLinesRemaining = hunk.groupValues[2].toIntOrNull() ?: 1
            newLinesRemaining = hunk.groupValues[4].toIntOrNull() ?: 1
            endLine = index
            continue
        }
        if (!sawHunk) {
            endLine = index
            if (line.startsWith("Binary files ") && line.endsWith(" differ")) break
            continue
        }
        if (oldLinesRemaining == 0 && newLinesRemaining == 0) {
            if (line.startsWith("\\ No newline at end of file")) {
                endLine = index
                continue
            }
            break
        }
        when (line.firstOrNull()) {
            ' ' -> {
                oldLinesRemaining = (oldLinesRemaining - 1).coerceAtLeast(0)
                newLinesRemaining = (newLinesRemaining - 1).coerceAtLeast(0)
            }
            '-' -> oldLinesRemaining = (oldLinesRemaining - 1).coerceAtLeast(0)
            '+' -> newLinesRemaining = (newLinesRemaining - 1).coerceAtLeast(0)
            '\\' -> Unit
            else -> break
        }
        endLine = index
    }
    return lines.take(endLine + 1).joinToString("\n").takeIf { it.isNotBlank() }
}

/** Path referenced by the diff's `+++`/`---` headers, stripping the `a/`/`b/` prefixes git adds. */
fun unifiedDiffPath(text: String): String? {
    val newHeader = text.lineSequence().firstOrNull { it.startsWith("+++ ") }
        ?.removePrefix("+++ ")?.substringBefore('\t')?.trim()
    val oldHeader = text.lineSequence().firstOrNull { it.startsWith("--- ") }
        ?.removePrefix("--- ")?.substringBefore('\t')?.trim()
    val candidate = newHeader?.takeUnless { it == "/dev/null" } ?: oldHeader?.takeUnless { it == "/dev/null" }
    return candidate?.removePrefix("b/")?.removePrefix("a/")
}

/** Parses [text] as a unified diff if it looks like one, otherwise returns null. */
fun detectUnifiedDiff(text: String): AgentFileDiff? {
    if (!looksLikeUnifiedDiff(text)) return null
    // AgentFileDiff represents one file. Rendering a multi-file patch as one file resets line
    // numbers at each later hunk and can let a binary entry hide earlier textual changes.
    val fileCount = text.lineSequence().count { it.startsWith("diff --git ") }
        .takeIf { it > 0 }
        ?: countHeaderPairsOutsideHunks(text)
    if (fileCount != 1) return null
    val path = unifiedDiffPath(text) ?: "diff"
    return runCatching { parseUnifiedDiff(text, path) }.getOrNull()
        ?.takeIf { it.isBinary || it.lines.isNotEmpty() }
}

private fun countHeaderPairsOutsideHunks(text: String): Int {
    var count = 0
    var awaitingNewHeader = false
    var oldLinesRemaining = 0
    var newLinesRemaining = 0
    text.lineSequence().forEach { line ->
        HunkHeader.find(line)?.let { hunk ->
            oldLinesRemaining = hunk.groupValues[2].toIntOrNull() ?: 1
            newLinesRemaining = hunk.groupValues[4].toIntOrNull() ?: 1
            awaitingNewHeader = false
            return@forEach
        }
        if (oldLinesRemaining > 0 || newLinesRemaining > 0) {
            when (line.firstOrNull()) {
                ' ' -> {
                    oldLinesRemaining = (oldLinesRemaining - 1).coerceAtLeast(0)
                    newLinesRemaining = (newLinesRemaining - 1).coerceAtLeast(0)
                }
                '-' -> oldLinesRemaining = (oldLinesRemaining - 1).coerceAtLeast(0)
                '+' -> newLinesRemaining = (newLinesRemaining - 1).coerceAtLeast(0)
            }
            return@forEach
        }
        when {
            line.startsWith("--- ") -> awaitingNewHeader = true
            awaitingNewHeader && line.startsWith("+++ ") -> {
                count += 1
                awaitingNewHeader = false
            }
            line.isNotBlank() -> awaitingNewHeader = false
        }
    }
    return count
}

/** Parses a unified diff into numbered add/delete/context lines for inline review. */
fun parseUnifiedDiff(text: String, path: String): AgentFileDiff {
    if (text.lineSequence().any { it.startsWith("Binary files ") && it.endsWith(" differ") }) {
        return AgentFileDiff(path = path, lines = emptyList(), isBinary = true)
    }

    val lines = mutableListOf<DiffLine>()
    var oldLine = 0
    var newLine = 0
    var inHunk = false
    var isNewFile = false

    text.lineSequence().forEach { raw ->
        when {
            raw.startsWith("diff ") || raw.startsWith("index ") || raw.startsWith("similarity ") -> Unit
            raw.startsWith("new file mode") -> isNewFile = true
            raw.startsWith("deleted file mode") -> Unit
            !inHunk && raw.startsWith("--- /dev/null") -> isNewFile = true
            !inHunk && raw.startsWith("--- ") -> Unit
            !inHunk && raw.startsWith("+++ ") -> Unit
            raw.startsWith("\\ No newline") -> Unit
            else -> {
                val hunk = HunkHeader.find(raw)
                if (hunk != null) {
                    oldLine = hunk.groupValues[1].toInt()
                    newLine = hunk.groupValues[3].toInt()
                    inHunk = true
                    return@forEach
                }
                if (!inHunk || raw.isEmpty()) return@forEach
                when (raw.first()) {
                    ' ' -> {
                        lines += DiffLine(DiffLineKind.Context, raw.drop(1), oldLine, newLine)
                        oldLine += 1
                        newLine += 1
                    }
                    '+' -> {
                        lines += DiffLine(DiffLineKind.Addition, raw.drop(1), null, newLine)
                        newLine += 1
                    }
                    '-' -> {
                        lines += DiffLine(DiffLineKind.Deletion, raw.drop(1), oldLine, null)
                        oldLine += 1
                    }
                }
            }
        }
    }

    return AgentFileDiff(
        path = path,
        lines = lines,
        isNewFile = isNewFile || (
            lines.isNotEmpty() &&
                lines.none { it.kind == DiffLineKind.Deletion || it.kind == DiffLineKind.Context }
            ),
    )
}

/** Builds an all-additions diff for a newly created (untracked) file. */
fun diffForNewFile(path: String, content: String): AgentFileDiff {
    val body = content.lines().let { rows ->
        if (content.endsWith("\n") && rows.lastOrNull() == "") rows.dropLast(1) else rows
    }
    val lines = body.mapIndexed { index, text ->
        DiffLine(DiffLineKind.Addition, text, oldLineNumber = null, newLineNumber = index + 1)
    }
    return AgentFileDiff(path = path, lines = lines, isNewFile = true)
}

/**
 * Pairs unified-diff lines into side-by-side rows: context on both sides,
 * then deletion/addition change groups aligned by index.
 */
fun buildSplitDiffPairs(lines: List<DiffLine>): List<SplitDiffPair> {
    if (lines.isEmpty()) return emptyList()
    val pairs = mutableListOf<SplitDiffPair>()
    var index = 0
    while (index < lines.size) {
        when (lines[index].kind) {
            DiffLineKind.Context -> {
                pairs += SplitDiffPair(old = lines[index], new = lines[index])
                index += 1
            }
            DiffLineKind.Deletion, DiffLineKind.Addition -> {
                val deletions = mutableListOf<DiffLine>()
                val additions = mutableListOf<DiffLine>()
                while (index < lines.size && lines[index].kind == DiffLineKind.Deletion) {
                    deletions += lines[index]
                    index += 1
                }
                while (index < lines.size && lines[index].kind == DiffLineKind.Addition) {
                    additions += lines[index]
                    index += 1
                }
                val count = maxOf(deletions.size, additions.size)
                for (offset in 0 until count) {
                    pairs += SplitDiffPair(
                        old = deletions.getOrNull(offset),
                        new = additions.getOrNull(offset),
                    )
                }
            }
        }
    }
    return pairs
}

data class SplitDiffPair(
    val old: DiffLine?,
    val new: DiffLine?,
) {
    val isContext: Boolean
        get() = old?.kind == DiffLineKind.Context && new?.kind == DiffLineKind.Context
}
