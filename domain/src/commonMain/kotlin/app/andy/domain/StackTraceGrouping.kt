package app.andy.domain

import app.andy.model.LogcatEntry
import app.andy.model.StackTraceBlock

/**
 * Groups a flat stream of [LogcatEntry] rows into contiguous stack-trace blocks.
 *
 * Logcat yields one entry per physical line, so a Java/Kotlin crash or ANR dump arrives as N
 * unrelated-looking entries. This pure, platform-independent function reassembles them so the
 * UI can mark a block, retrace it as a unit, and let the user expand/collapse it.
 *
 * Heuristics:
 * - A block starts at a `FATAL EXCEPTION` header line, or a line whose message begins with an
 *   exception/error class name (`java.lang.NullPointerException: ...`).
 * - Once open, lines sharing the same [LogcatEntry.tag] and [LogcatEntry.pid] as the header are
 *   folded into the block's frames — this covers the `Process:` line, the exception message
 *   line, `\tat ...` frames, and `Caused by:` / `... N more` continuation lines alike.
 * - Lines from a different tag or pid are treated as interleaved output from another
 *   process/logger and are skipped without closing the block, so multi-process logs do not
 *   fracture a single trace.
 * - A second `FATAL EXCEPTION` on the same tag/pid starts a new block rather than extending the
 *   current one, so back-to-back crashes are not merged together.
 */
fun groupStackTraces(entries: List<LogcatEntry>): List<StackTraceBlock> {
    val blocks = mutableListOf<StackTraceBlock>()
    var open: OpenBlock? = null

    fun closeCurrent(endIndex: Int) {
        val current = open ?: return
        if (current.frameIndexes.isNotEmpty()) {
            blocks += StackTraceBlock(
                startIndex = current.startIndex,
                endIndex = endIndex,
                header = current.header,
                frames = current.frameIndexes.map { entries[it].message },
            )
        }
        open = null
    }

    entries.forEachIndexed { index, entry ->
        val current = open
        if (current != null) {
            val sameProcess = entry.tag == current.tag &&
                (current.pid == null || entry.pid == null || entry.pid == current.pid)
            if (!sameProcess) {
                // Interleaved output from another tag/process; keep the block open and wait.
                return@forEachIndexed
            }
            if (isHeaderLine(entry.message) && current.frameIndexes.isNotEmpty()) {
                // A fresh crash on the same tag/pid — close the current block and start anew.
                closeCurrent(index - 1)
                open = OpenBlock(index, entry.tag, entry.pid, entry.message.trim())
                return@forEachIndexed
            }
            current.frameIndexes += index
            return@forEachIndexed
        }
        if (isHeaderLine(entry.message)) {
            open = OpenBlock(index, entry.tag, entry.pid, entry.message.trim())
        }
    }
    closeCurrent(entries.lastIndex)
    return blocks
}

private data class OpenBlock(
    val startIndex: Int,
    val tag: String,
    val pid: String?,
    val header: String,
    val frameIndexes: MutableList<Int> = mutableListOf(),
)

private val exceptionHeaderRegex = Regex("""^[A-Za-z_][\w.$]*(?:Exception|Error)(:|$)""")

private fun isHeaderLine(message: String): Boolean {
    val trimmed = message.trim()
    if (trimmed.contains("FATAL EXCEPTION")) return true
    if (trimmed.startsWith("ANR in ")) return true
    return exceptionHeaderRegex.containsMatchIn(trimmed)
}
