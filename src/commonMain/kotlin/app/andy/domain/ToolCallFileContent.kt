package app.andy.domain

import app.andy.model.AgentFileDiff
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind

/** Parsed file reference from an ACP tool-call detail block. */
data class ToolCallFileContent(
    val path: String,
    val oldText: String?,
    val newText: String?,
) {
    val hasDiff: Boolean get() = !oldText.isNullOrEmpty() || !newText.isNullOrEmpty()
}

private const val OldMarker = "\n--- old\n"
private const val NewMarker = "\n+++ new\n"

/** Parses tool output shaped like ACP [ToolCallContent.Diff] rendering. */
fun parseToolCallFileContent(text: String): ToolCallFileContent? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    val oldIndex = trimmed.indexOf(OldMarker)
    val newIndex = trimmed.indexOf(NewMarker)
    if (oldIndex >= 0 && newIndex > oldIndex) {
        val path = trimmed.substring(0, oldIndex)
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { looksLikeFilePath(it) }
            ?: return null
        val oldText = trimmed.substring(oldIndex + OldMarker.length, newIndex)
        val newText = trimmed.substring(newIndex + NewMarker.length)
        return ToolCallFileContent(path = path, oldText = oldText, newText = newText)
    }

    val firstLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (!looksLikeFilePath(firstLine)) return null
    val remainder = trimmed.lineSequence().dropWhile { it.isBlank() }.drop(1).joinToString("\n")
    return ToolCallFileContent(
        path = firstLine,
        oldText = null,
        newText = remainder.takeIf { it.isNotBlank() },
    )
}

fun diffFromToolCallFileContent(content: ToolCallFileContent): AgentFileDiff =
    diffTextLines(content.path, content.oldText, content.newText)

internal fun looksLikeFilePath(text: String): Boolean {
    if (text.isBlank()) return false
    if (text.startsWith("/") || text.startsWith("~/")) return true
    if (Regex("""^[A-Za-z]:\\""").containsMatchIn(text)) return true
    return text.contains('/') && text.contains('.')
}

/** Builds numbered diff lines from optional before/after snapshots. */
fun diffTextLines(path: String, oldText: String?, newText: String?): AgentFileDiff {
    val oldLines = oldText.orEmpty().normalizedLines()
    val newLines = newText.orEmpty().normalizedLines()
    when {
        oldLines.isEmpty() && newLines.isEmpty() ->
            return AgentFileDiff(path = path, lines = emptyList())
        oldLines.isEmpty() ->
            return diffForNewFile(path, newText.orEmpty())
        newLines.isEmpty() -> {
            val lines = oldLines.mapIndexed { index, line ->
                DiffLine(DiffLineKind.Deletion, line, oldLineNumber = index + 1, newLineNumber = null)
            }
            return AgentFileDiff(path = path, lines = lines)
        }
    }
    val operations = lineDiffOperations(oldLines, newLines)
    var oldLine = 1
    var newLine = 1
    val lines = operations.map { operation ->
        when (operation) {
            is LineDiffOp.Context -> {
                val line = DiffLine(DiffLineKind.Context, operation.text, oldLine, newLine)
                oldLine += 1
                newLine += 1
                line
            }
            is LineDiffOp.Deletion -> {
                val line = DiffLine(DiffLineKind.Deletion, operation.text, oldLine, null)
                oldLine += 1
                line
            }
            is LineDiffOp.Addition -> {
                val line = DiffLine(DiffLineKind.Addition, operation.text, null, newLine)
                newLine += 1
                line
            }
        }
    }
    return AgentFileDiff(path = path, lines = lines)
}

private fun String.normalizedLines(): List<String> {
    val rows = lines()
    return if (endsWith("\n") && rows.lastOrNull() == "") rows.dropLast(1) else rows
}

private sealed interface LineDiffOp {
    data class Context(val text: String) : LineDiffOp
    data class Deletion(val text: String) : LineDiffOp
    data class Addition(val text: String) : LineDiffOp
}

private fun lineDiffOperations(oldLines: List<String>, newLines: List<String>): List<LineDiffOp> {
  if (oldLines == newLines) return oldLines.map(LineDiffOp::Context)
  val lcs = longestCommonSubsequence(oldLines, newLines)
  val operations = mutableListOf<LineDiffOp>()
  var oldIndex = 0
  var newIndex = 0
  var lcsIndex = 0
  while (oldIndex < oldLines.size || newIndex < newLines.size) {
    val nextCommon = lcs.getOrNull(lcsIndex)
    while (oldIndex < oldLines.size && oldLines[oldIndex] != nextCommon) {
      operations += LineDiffOp.Deletion(oldLines[oldIndex])
      oldIndex += 1
    }
    while (newIndex < newLines.size && newLines[newIndex] != nextCommon) {
      operations += LineDiffOp.Addition(newLines[newIndex])
      newIndex += 1
    }
    if (nextCommon != null) {
      operations += LineDiffOp.Context(nextCommon)
      oldIndex += 1
      newIndex += 1
      lcsIndex += 1
    } else {
      break
    }
  }
  while (oldIndex < oldLines.size) {
    operations += LineDiffOp.Deletion(oldLines[oldIndex])
    oldIndex += 1
  }
  while (newIndex < newLines.size) {
    operations += LineDiffOp.Addition(newLines[newIndex])
    newIndex += 1
  }
  return operations
}

private fun longestCommonSubsequence(left: List<String>, right: List<String>): List<String> {
  val table = Array(left.size + 1) { IntArray(right.size + 1) }
  for (row in 1..left.size) {
    for (column in 1..right.size) {
      table[row][column] = if (left[row - 1] == right[column - 1]) {
        table[row - 1][column - 1] + 1
      } else {
        maxOf(table[row - 1][column], table[row][column - 1])
      }
    }
  }
  val result = ArrayDeque<String>()
  var row = left.size
  var column = right.size
  while (row > 0 && column > 0) {
    when {
      left[row - 1] == right[column - 1] -> {
        result.addFirst(left[row - 1])
        row -= 1
        column -= 1
      }
      table[row - 1][column] >= table[row][column - 1] -> row -= 1
      else -> column -= 1
    }
  }
  return result.toList()
}
