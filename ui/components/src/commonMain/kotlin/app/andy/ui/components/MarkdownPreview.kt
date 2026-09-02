package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.andy.ui.components.AndyCheckbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Ambient handler for markdown links that look like source-file references rather than web
 * URLs. Returning `true` swallows the click (e.g. to open the file in Andy's own code viewer);
 * returning `false` falls through to the platform's normal [LocalUriHandler] (the OS browser).
 * Defaults to always falling through, so screens that don't provide one keep prior behavior.
 */
val LocalOnOpenFileLink = staticCompositionLocalOf<(String) -> Boolean> { { false } }

/** Real URI schemes (`http:`, `mailto:`, `file:`, …) are at least two characters before the colon, which excludes single-letter Windows drive prefixes like `C:\`. */
private val UriSchemeRegex = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]+:""")

/** A markdown link href is treated as a source-file reference unless it's a real web/mail URL. */
private fun looksLikeFileLink(uri: String): Boolean {
    val trimmed = uri.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return false
    if (trimmed.startsWith("file:", ignoreCase = true)) return true
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return false
    if (UriSchemeRegex.containsMatchIn(trimmed)) return false
    return true
}

/**
 * Markdown preview for scratchpads and notes.
 * Uses [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer).
 * When [onTextChange] is provided, task checkboxes toggle `[ ]` / `[x]` in the source.
 */
@Composable
fun MarkdownPreview(
    text: String,
    modifier: Modifier = Modifier,
    onTextChange: ((String) -> Unit)? = null,
) {
    Box(
        modifier
            .background(Panel, RoundedCornerShape(AndyRadius.Control))
            .border(1.dp, PaneDividerTint, RoundedCornerShape(AndyRadius.Control)),
    ) {
        if (text.isBlank()) {
            Text(
                "Nothing to preview yet.",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                modifier = Modifier.padding(AndySpace.Space5),
            )
        } else {
            AndyMarkdown(
                text = text,
                density = AndyMarkdownDensity.Preview,
                onTextChange = onTextChange,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AndySpace.Space5),
            )
        }
    }
}

/**
 * Full GFM markdown for agent chat bubbles. Keeps previous content visible while
 * streamed text is re-parsed, and avoids nested scrolling inside the transcript list.
 */
@Composable
fun ChatMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = 19.sp,
    density: AndyMarkdownDensity = AndyMarkdownDensity.Chat,
    /**
     * Promote single newlines to Markdown hard breaks. Used for tool-detail bodies so
     * plain source keeps its line structure. Leave false for provider chat markdown.
     */
    preserveLineBreaks: Boolean = false,
    /** Overrides the code/inline-code color, e.g. the plan card's teal technical-token accent. */
    codeAccent: Color? = null,
) {
    if (text.isBlank()) return
    AndyMarkdown(
        text = if (preserveLineBreaks) text.withChatLineBreaks() else text,
        density = density,
        bodyLineHeight = lineHeight,
        codeAccent = codeAccent,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Preserves chat line breaks without changing blank-line paragraphs or fenced code blocks. */
fun String.withChatLineBreaks(): String {
    val lines = replace("\r\n", "\n").split('\n')
    var inFence = false
    return lines.mapIndexed { index, line ->
        val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
        val next = lines.getOrNull(index + 1)
        val nextIsFence = next?.trimStart()?.let { it.startsWith("```") || it.startsWith("~~~") } == true
        val hardBreak = !inFence && !fence && !nextIsFence && line.isNotBlank() && next != null && next.isNotBlank()
        val renderedLine = if (hardBreak && !line.endsWith("  ") && !line.endsWith("\\")) "$line  " else line
        if (fence) inFence = !inFence
        renderedLine
    }.joinToString("\n")
}

/**
 * Agents often demonstrate markdown by wrapping an entire reply in a single outer
 * ` ```markdown ` fence, sometimes nesting further fences inside it (e.g. a code
 * sample). Per CommonMark, a fence only closes on a bare line of backticks/tildes
 * with no info string, so an inner ` ```lang ` line doesn't close it — the whole
 * reply parses as one literal code block and nothing renders as real
 * headings/lists/tables. If the whole message is exactly that pattern, strip the
 * outer fence so the interior renders as markdown.
 */
fun String.unwrapOuterMarkdownFence(): String {
    val trimmed = trim()
    val lines = trimmed.lines()
    if (lines.size < 3) return this

    val first = lines.first().trim()
    val fenceChar = when {
        first.startsWith("```") -> '`'
        first.startsWith("~~~") -> '~'
        else -> return this
    }
    val openLen = first.takeWhile { it == fenceChar }.length
    val info = first.substring(openLen).trim().lowercase()
    if (info != "markdown" && info != "md") return this

    val last = lines.last().trim()
    val closesFence = last.isNotEmpty() && last.all { it == fenceChar } && last.length >= openLen
    if (!closesFence) return this

    return lines.subList(1, lines.size - 1).joinToString("\n")
}

enum class AndyMarkdownDensity {
    Preview,
    Chat,
    /** Muted, compact body for thinking asides. */
    Thinking,
}

@Composable
private fun AndyMarkdown(
    text: String,
    density: AndyMarkdownDensity,
    modifier: Modifier = Modifier,
    bodyLineHeight: TextUnit = when (density) {
        AndyMarkdownDensity.Thinking -> 16.sp
        AndyMarkdownDensity.Chat -> 19.sp
        AndyMarkdownDensity.Preview -> 20.sp
    },
    onTextChange: ((String) -> Unit)? = null,
    codeAccent: Color? = null,
) {
    // Only unwrap for read-only content: checkbox toggling below computes offsets
    // into the parsed source and writes them straight back through onTextChange,
    // so mutating what gets parsed would silently drop the fence from saved text.
    val unwrapped = if (onTextChange == null) text.unwrapOuterMarkdownFence() else text
    val markdownState = rememberMarkdownState(unwrapped, retainState = true)
    val highlightsBuilder = rememberAndyHighlightsBuilder()
    val thinking = density == AndyMarkdownDensity.Thinking
    val body = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = if (thinking) MonoFont else DisplayFont,
        fontSize = if (thinking) 11.sp else 14.sp,
        lineHeight = bodyLineHeight,
        color = if (thinking) TextSecondary else TextPrimary,
    )
    val headingScale = when (density) {
        AndyMarkdownDensity.Preview -> 1f
        AndyMarkdownDensity.Chat -> 0.82f
        AndyMarkdownDensity.Thinking -> 0.7f
    }
    val onOpenFileLink = LocalOnOpenFileLink.current
    val browserUriHandler = LocalUriHandler.current
    val uriHandler = remember(onOpenFileLink, browserUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (looksLikeFileLink(uri) && onOpenFileLink(uri)) return
                browserUriHandler.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
    Markdown(
        markdownState = markdownState,
        colors = markdownColor(
            text = if (thinking) TextSecondary else TextPrimary,
            // Thinking/tool rows sit on a near-black aside — keep code chrome strong enough that a
            // Read fragment's less-indented lines still read as inside the fence, not as body text.
            codeBackground = AndyColors.Neutral850.copy(alpha = if (thinking) 0.72f else 0.55f),
            inlineCodeBackground = AndyColors.Neutral700.copy(alpha = if (thinking) 0.45f else 0.72f),
            dividerColor = PaneDividerTint,
            tableBackground = AndyColors.Neutral850.copy(alpha = if (thinking) 0.4f else 0.65f),
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.displayLarge.copy(
                fontFamily = DisplayFont,
                fontSize = (26 * headingScale).sp,
                lineHeight = (32 * headingScale).sp,
            ),
            h2 = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = DisplayFont,
                fontSize = (22 * headingScale).sp,
                lineHeight = (28 * headingScale).sp,
            ),
            h3 = MaterialTheme.typography.titleMedium.copy(
                fontFamily = DisplayFont,
                fontSize = (18 * headingScale).sp,
                lineHeight = (24 * headingScale).sp,
            ),
            h4 = MaterialTheme.typography.titleMedium.copy(
                fontFamily = DisplayFont,
                fontSize = (16 * headingScale).sp,
                lineHeight = (22 * headingScale).sp,
            ),
            h5 = MaterialTheme.typography.titleMedium.copy(
                fontFamily = DisplayFont,
                fontSize = (14 * headingScale).sp,
                lineHeight = (20 * headingScale).sp,
            ),
            h6 = MaterialTheme.typography.titleMedium.copy(
                fontFamily = DisplayFont,
                fontSize = (13 * headingScale).sp,
                lineHeight = (18 * headingScale).sp,
            ),
            text = body,
            paragraph = body,
            quote = body.copy(fontSize = if (thinking) 11.sp else 13.sp, lineHeight = bodyLineHeight),
            code = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFont,
                fontSize = if (thinking) 10.sp else 12.sp,
                lineHeight = if (thinking) 15.sp else 18.sp,
                color = codeAccent ?: (if (thinking) TextSecondary else TextPrimary),
            ),
            inlineCode = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFont,
                fontSize = if (thinking) 10.sp else 12.sp,
                color = codeAccent ?: (if (thinking) TextSecondary else TextPrimary.copy(alpha = 0.95f)),
            ),
            ordered = body,
            bullet = body,
            list = body,
            textLink = TextLinkStyles(
                style = body.copy(
                    color = Cyan.copy(alpha = if (thinking) 0.82f else 0.92f),
                    textDecoration = TextDecoration.None,
                ).toSpanStyle(),
            ),
        ),
        padding = when (density) {
            AndyMarkdownDensity.Chat -> markdownPadding(
                block = 1.dp,
                list = 2.dp,
                listItemTop = 2.dp,
                listItemBottom = 2.dp,
                listIndent = 6.dp,
                codeBlock = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            )
            AndyMarkdownDensity.Thinking -> markdownPadding(
                block = 0.dp,
                list = 1.dp,
                listItemTop = 1.dp,
                listItemBottom = 1.dp,
                listIndent = 6.dp,
                codeBlock = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            )
            AndyMarkdownDensity.Preview -> markdownPadding()
        },
        dimens = markdownDimens(
            tableCellPadding = when (density) {
                AndyMarkdownDensity.Chat -> 6.dp
                AndyMarkdownDensity.Thinking -> 4.dp
                AndyMarkdownDensity.Preview -> 16.dp
            },
        ),
        components = markdownComponents(
            codeBlock = {
                SafeMarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    style = it.typography.code,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = !thinking,
                )
            },
            codeFence = {
                SafeMarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    style = it.typography.code,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = !thinking,
                )
            },
            checkbox = { model ->
                MarkdownCheckBox(
                    content = model.content,
                    node = model.node,
                    style = model.typography.text,
                    checkedIndicator = { checked, checkboxModifier ->
                        // Material's default 48dp touch target sits below the list text baseline.
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            AndyCheckbox(
                                checked = checked,
                                onCheckedChange = onTextChange?.let { update ->
                                    {
                                        val next = toggleMarkdownCheckbox(
                                            content = model.content,
                                            startOffset = model.node.startOffset,
                                            endOffset = model.node.endOffset,
                                        )
                                        if (next != null) update(next)
                                    }
                                },
                                modifier = checkboxModifier
                                    .padding(top = 1.dp)
                                    .size(18.dp)
                                    .semantics {
                                        role = Role.Checkbox
                                        stateDescription = if (checked) "Checked" else "Unchecked"
                                    },
                            )
                        }
                    },
                )
            },
            // Library defaults to maxLines=1 + ellipsis in table cells, which clips chat prose.
            table = { model ->
                MarkdownTable(
                    content = model.content,
                    node = model.node,
                    style = model.typography.table,
                    headerBlock = { content, header, tableWidth, style ->
                        MarkdownTableHeader(
                            content = content,
                            header = header,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                    rowBlock = { content, row, tableWidth, style ->
                        MarkdownTableRow(
                            content = content,
                            header = row,
                            tableWidth = tableWidth,
                            style = style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    },
                )
            },
        ),
        modifier = modifier,
    )
    }
}

/** Toggle a GFM task marker at [startOffset], [endOffset] inside [content]. */
fun toggleMarkdownCheckbox(
    content: String,
    startOffset: Int,
    endOffset: Int,
): String? {
    if (startOffset < 0 || endOffset > content.length || startOffset >= endOffset) return null
    val token = content.substring(startOffset, endOffset)
    val replacement = when {
        CheckedTaskPattern.containsMatchIn(token) -> CheckedTaskPattern.replaceFirst(token, "[ ]")
        UncheckedTaskPattern.containsMatchIn(token) -> UncheckedTaskPattern.replaceFirst(token, "[x]")
        else -> return null
    }
    return content.replaceRange(startOffset, endOffset, replacement)
}

private val CheckedTaskPattern = Regex("""\[[xX]]""")
private val UncheckedTaskPattern = Regex("""\[ ]""")

@Composable
private fun rememberAndyHighlightsBuilder(): Highlights.Builder {
    val isLight = AndyColors.isLight
    return remember(isLight) {
        Highlights.Builder().theme(andyChatSyntaxTheme(isLight))
    }
}

/** Matches the desktop host editor palette so chat code blocks feel consistent with Andy. */
private fun andyChatSyntaxTheme(isLight: Boolean): SyntaxTheme = if (isLight) {
    SyntaxThemes.atom(darkMode = false)
} else {
    SyntaxTheme(
        key = "andy",
        code = 0xFFE4DED0.toInt(),
        keyword = 0xFFD18A4B.toInt(),
        string = 0xFF94C17A.toInt(),
        literal = 0xFFE3B05E.toInt(),
        comment = 0xFF8E8779.toInt(),
        metadata = 0xFF88AFC8.toInt(),
        multilineComment = 0xFF8E8779.toInt(),
        punctuation = 0xFFE26F5C.toInt(),
        mark = 0xFFB865FF.toInt(),
    )
}
