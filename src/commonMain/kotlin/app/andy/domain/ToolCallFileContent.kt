package app.andy.domain

import app.andy.model.AcpToolCallPresentation
import app.andy.model.AgentFileDiff
import app.andy.model.AgentToolKind
import app.andy.model.DiffLine
import app.andy.model.DiffLineKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parsed file reference from an ACP tool-call detail block. */
data class ToolCallFileContent(
    val path: String,
    val oldText: String?,
    val newText: String?,
    val extraDetail: String? = null,
) {
    /** ACP diff payloads always carry an old snapshot (possibly empty for a new file). */
    val hasDiff: Boolean get() = oldText != null
}

private const val OldMarker = "\n--- old\n"
private const val NewMarker = "\n+++ new\n"

/** Parses tool output shaped like ACP [ToolCallContent.Diff] rendering. */
fun parseToolCallFileContent(text: String): ToolCallFileContent? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || looksLikeProviderPayload(trimmed)) return null

    val oldIndex = trimmed.indexOf(OldMarker)
    val newIndex = trimmed.indexOf(NewMarker)
    if (oldIndex >= 0 && newIndex > oldIndex) {
        val path = trimmed.substring(0, oldIndex)
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { looksLikeStructuredFilePath(it) }
            ?: return null
        val oldText = trimmed.substring(oldIndex + OldMarker.length, newIndex)
        val newAndExtra = trimmed.substring(newIndex + NewMarker.length)
        val separatorIndex = newAndExtra.indexOf(AcpToolCallPresentation.DetailSeparator)
        val newText = if (separatorIndex >= 0) newAndExtra.substring(0, separatorIndex) else newAndExtra
        val extraDetail = if (separatorIndex >= 0) {
            newAndExtra.substring(separatorIndex + AcpToolCallPresentation.DetailSeparator.length)
                .takeIf { it.isNotBlank() }
        } else {
            null
        }
        return ToolCallFileContent(path = path, oldText = oldText, newText = newText, extraDetail = extraDetail)
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

private val toolArgumentJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val PathArgumentKeys = listOf("path", "file_path", "filePath", "file", "target_file", "uri")
private val ExplicitOldTextArgumentKeys = listOf("old_string", "oldText", "old_str")
private val ExplicitNewTextArgumentKeys = listOf("new_string", "newText", "new_str", "replace")
private val AmbiguousOldTextArgumentKeys = listOf("old", "before", "search")
private val AmbiguousNewTextArgumentKeys = listOf("new", "after", "content", "contents")

/**
 * Edit and write tools frequently send their arguments as JSON instead of a rendered diff.
 * Recognizing that shape lets the transcript show a real diff rather than the payload.
 */
fun parseToolCallFileArguments(text: String, kind: AgentToolKind? = null): ToolCallFileContent? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return null
    val obj = runCatching { toolArgumentJson.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
    val path = PathArgumentKeys.firstNotNullOfOrNull { obj.stringValue(it) }
        ?.takeIf { looksLikeStructuredFilePath(it) }
        ?: return null
    val editKind = kind == AgentToolKind.Edit || kind == AgentToolKind.Delete || kind == AgentToolKind.Move
    val oldKeys = ExplicitOldTextArgumentKeys + if (editKind) AmbiguousOldTextArgumentKeys else emptyList()
    val newKeys = ExplicitNewTextArgumentKeys + if (editKind) AmbiguousNewTextArgumentKeys else emptyList()
    val oldText = oldKeys.firstNotNullOfOrNull { obj.stringValue(it) }
    val newText = newKeys.firstNotNullOfOrNull { obj.stringValue(it) }
    if (oldText == null && newText == null) return null
    return ToolCallFileContent(path = path, oldText = oldText, newText = newText)
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

fun diffFromToolCallFileContent(content: ToolCallFileContent): AgentFileDiff =
    diffTextLines(content.path, content.oldText, content.newText)

private val WindowsDriveLetter = Regex("""^[A-Za-z]:\\""")
private val ProviderAssignment = Regex("""^[A-Za-z][A-Za-z0-9 _-]*\s*=""")

private fun looksLikeProviderPayload(text: String): Boolean {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return firstLine.startsWith("{") ||
        firstLine.startsWith("[") ||
        firstLine.startsWith("- **") ||
        ProviderAssignment.containsMatchIn(firstLine)
}

private fun looksLikeStructuredFilePath(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isNotBlank() &&
        trimmed.length <= 512 &&
        !trimmed.contains('\n') &&
        !looksLikeProviderPayload(trimmed)
}

internal fun looksLikeFilePath(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isBlank() || trimmed.length > 512 || trimmed.contains('\n')) return false
    if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("- **")) return false
    // Paths from ACP's structured path field or the dedicated first line may legally contain spaces.
    // Payload-shaped outer text is rejected before reaching this helper.
    if (trimmed.startsWith("/") || trimmed.startsWith("~/")) return true
    if (WindowsDriveLetter.containsMatchIn(trimmed)) return true
    return trimmed.contains('/') && trimmed.contains('.')
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

private const val MaxLcsCells = 1_000_000L
private const val MaxLcsDimension = 10_000

private fun lineDiffOperations(oldLines: List<String>, newLines: List<String>): List<LineDiffOp> {
  if (oldLines == newLines) return oldLines.map(LineDiffOp::Context)
  // The exact LCS implementation is quadratic in memory and this function can run while an
  // expanded tool row is being composed. Keep large snapshots responsive by falling back to a
  // linear all-delete/all-add representation rather than allocating an unbounded matrix.
  if (oldLines.size > MaxLcsDimension ||
      newLines.size > MaxLcsDimension ||
      oldLines.size.toLong() * newLines.size.toLong() > MaxLcsCells
  ) {
    return oldLines.map(LineDiffOp::Deletion) + newLines.map(LineDiffOp::Addition)
  }
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
