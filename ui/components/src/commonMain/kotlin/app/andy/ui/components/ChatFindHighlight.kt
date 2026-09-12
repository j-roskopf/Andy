package app.andy.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * In-chat find highlight ambient. Provided per transcript row so markdown/plain text can
 * paint match backgrounds without threading the query through every bubble API.
 */
@Immutable
data class ChatFindHighlight(
    val query: String,
    /** Range into this row's searchable/source text for the active hit, if any. */
    val activeRange: IntRange? = null,
) {
    val trimmedQuery: String get() = query.trim()
    val isActive: Boolean get() = trimmedQuery.isNotEmpty()
}

val LocalChatFindHighlight = staticCompositionLocalOf<ChatFindHighlight?> { null }

val ChatFindMatchBackground: Color = Color(0x66E2A400)
val ChatFindActiveMatchBackground: Color = Color(0xCCFBCE03)
val ChatFindActiveMatchForeground: Color = Color(0xFF1A1408)
val ChatFindRowWash: Color = Color(0x22E2A400)
val ChatFindActiveRowWash: Color = Color(0x33FBCE03)

/** All non-overlapping, case-insensitive occurrences of [needle] in [haystack]. */
fun findLiteralRanges(haystack: String, needle: String): List<IntRange> {
    val query = needle.trim()
    if (query.isEmpty() || haystack.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var start = 0
    while (start <= haystack.length - query.length) {
        val idx = haystack.indexOf(query, startIndex = start, ignoreCase = true)
        if (idx < 0) break
        ranges += idx until (idx + query.length)
        start = idx + query.length
    }
    return ranges
}

/**
 * Build an [AnnotatedString] with find-match backgrounds. When [activeRange] overlaps a hit
 * (relative to [text]), that span uses the stronger active style.
 */
fun highlightFindMatches(
    text: String,
    query: String,
    activeRange: IntRange? = null,
): AnnotatedString {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    val ranges = findLiteralRanges(text, trimmed)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        for (range in ranges) {
            if (range.first > cursor) {
                append(text.substring(cursor, range.first))
            }
            val active = activeRange != null && rangesOverlap(range, activeRange)
            withStyle(
                SpanStyle(
                    background = if (active) ChatFindActiveMatchBackground else ChatFindMatchBackground,
                    color = if (active) ChatFindActiveMatchForeground else Color.Unspecified,
                ),
            ) {
                append(text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

fun AnnotatedString.Builder.appendFindHighlighted(
    text: String,
    query: String,
    activeRangeInText: IntRange? = null,
) {
    append(highlightFindMatches(text, query, activeRangeInText))
}

private fun rangesOverlap(a: IntRange, b: IntRange): Boolean =
    a.first <= b.last && b.first <= a.last

/** Map an active range in the full source string into a leaf substring's local coordinates. */
fun activeRangeInLeaf(
    leafStart: Int,
    leafEnd: Int,
    activeRange: IntRange?,
): IntRange? {
    if (activeRange == null) return null
    val start = maxOf(activeRange.first, leafStart)
    val endExclusive = minOf(activeRange.last + 1, leafEnd)
    if (start >= endExclusive) return null
    return (start - leafStart) until (endExclusive - leafStart)
}
