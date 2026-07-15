package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(
        val text: String,
        val marker: String,
        val checked: Boolean? = null,
        val indent: Int = 0,
    ) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

@Composable
internal fun MarkdownPreview(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Box(
        modifier
            .background(AndyColors.Neutral900.copy(alpha = 0.62f), RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, AndyColors.Neutral100.copy(alpha = 0.18f), RoundedCornerShape(AndyRadius.R2)),
    ) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AndySpace.S4),
                verticalArrangement = Arrangement.spacedBy(AndySpace.S3),
            ) {
                if (blocks.isEmpty()) {
                    Text("Nothing to preview yet.", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
                } else {
                    blocks.forEach { block -> MarkdownBlockContent(block) }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockContent(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val size = when (block.level) {
                1 -> 26.sp
                2 -> 22.sp
                3 -> 18.sp
                4 -> 16.sp
                else -> 14.sp
            }
            val annotated = remember(block.text) { parseInlineMarkdown(block.text) }
            Text(
                annotated,
                color = TextPrimary,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = size,
                lineHeight = size * 1.25f,
            )
        }
        is MarkdownBlock.Paragraph -> MarkdownBodyText(block.text)
        is MarkdownBlock.ListItem -> {
            Row(
                Modifier.fillMaxWidth().padding(start = (block.indent * 16).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when (block.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> block.marker
                    },
                    color = if (block.checked == true) Green else Rust,
                    fontFamily = MonoFont,
                    fontSize = 12.sp,
                )
                MarkdownBodyText(block.text, Modifier.weight(1f))
            }
        }
        is MarkdownBlock.Quote -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(3.dp).height(22.dp).background(Cyan, RoundedCornerShape(2.dp)))
                Text(
                    parseInlineMarkdown(block.text),
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is MarkdownBlock.Code -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(AndyColors.Neutral850, RoundedCornerShape(AndyRadius.R2))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R2))
                    .padding(AndySpace.S3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.language?.takeIf { it.isNotBlank() }?.let { language ->
                    Text(language.uppercase(), color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(block.text, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        MarkdownBlock.Divider -> Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun MarkdownBodyText(text: String, modifier: Modifier = Modifier) {
    val annotated = remember(text) { parseInlineMarkdown(text) }
    Text(
        annotated,
        color = TextPrimary,
        fontFamily = DisplayFont,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = modifier,
    )
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" ") { it.trim() })
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val language = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                code += lines[index]
                index++
            }
            blocks += MarkdownBlock.Code(language, code.joinToString("\n"))
        } else if (trimmed.isEmpty()) {
            flushParagraph()
        } else {
            val heading = HeadingPattern.matchEntire(line)
            val task = TaskPattern.matchEntire(line)
            val unordered = UnorderedListPattern.matchEntire(line)
            val ordered = OrderedListPattern.matchEntire(line)
            val quote = QuotePattern.matchEntire(line)
            when {
                heading != null -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
                }
                DividerPattern.matches(line) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Divider
                }
                task != null -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(
                        text = task.groupValues[3],
                        marker = "•",
                        checked = task.groupValues[2].equals("x", ignoreCase = true),
                        indent = task.groupValues[1].length / 2,
                    )
                }
                unordered != null -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(
                        text = unordered.groupValues[2],
                        marker = "•",
                        indent = unordered.groupValues[1].length / 2,
                    )
                }
                ordered != null -> {
                    flushParagraph()
                    blocks += MarkdownBlock.ListItem(
                        text = ordered.groupValues[3],
                        marker = "${ordered.groupValues[2]}.",
                        indent = ordered.groupValues[1].length / 2,
                    )
                }
                quote != null -> {
                    flushParagraph()
                    blocks += MarkdownBlock.Quote(quote.groupValues[1])
                }
                else -> paragraph += line
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

internal fun parseInlineMarkdown(markdown: String): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(markdown)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(markdown: String) {
    var index = 0
    while (index < markdown.length) {
        val remaining = markdown.substring(index)
        val link = InlineLinkPattern.find(remaining)
        when {
            markdown[index] == '\\' && index + 1 < markdown.length -> {
                append(markdown[index + 1])
                index += 2
            }
            link != null && link.range.first == 0 -> {
                withLink(
                    LinkAnnotation.Url(
                        url = link.groupValues[2],
                        styles = TextLinkStyles(
                            style = SpanStyle(color = Cyan, textDecoration = TextDecoration.Underline),
                            hoveredStyle = SpanStyle(color = Cyan.copy(alpha = 0.78f)),
                        ),
                    ),
                ) {
                    appendInlineMarkdown(link.groupValues[1])
                }
                index += link.value.length
            }
            markdown.startsWith("**", index) || markdown.startsWith("__", index) -> {
                val marker = markdown.substring(index, index + 2)
                val closing = markdown.indexOf(marker, startIndex = index + 2)
                if (closing > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineMarkdown(markdown.substring(index + 2, closing))
                    }
                    index = closing + 2
                } else {
                    append(marker)
                    index += 2
                }
            }
            markdown.startsWith("~~", index) -> {
                val closing = markdown.indexOf("~~", startIndex = index + 2)
                if (closing > index + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInlineMarkdown(markdown.substring(index + 2, closing))
                    }
                    index = closing + 2
                } else {
                    append("~~")
                    index += 2
                }
            }
            markdown[index] == '`' -> {
                val closing = markdown.indexOf('`', startIndex = index + 1)
                if (closing > index + 1) {
                    withStyle(SpanStyle(color = Rust, background = AndyColors.Neutral700, fontFamily = MonoFont)) {
                        append(markdown.substring(index + 1, closing))
                    }
                    index = closing + 1
                } else {
                    append('`')
                    index++
                }
            }
            markdown[index] == '*' || markdown[index] == '_' && (index == 0 || !markdown[index - 1].isLetterOrDigit()) -> {
                val marker = markdown[index]
                val closing = markdown.indexOf(marker, startIndex = index + 1)
                val closesEmphasis = closing > index + 1 && (marker != '_' || closing == markdown.lastIndex || !markdown[closing + 1].isLetterOrDigit())
                if (closesEmphasis) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(markdown.substring(index + 1, closing))
                    }
                    index = closing + 1
                } else {
                    append(marker)
                    index++
                }
            }
            else -> {
                append(markdown[index])
                index++
            }
        }
    }
}

private val HeadingPattern = Regex("""^\s{0,3}(#{1,6})\s+(.+)$""")
private val DividerPattern = Regex("""^\s{0,3}((\*\s*){3,}|(-\s*){3,}|(_\s*){3,})$""")
private val TaskPattern = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+(.+)$""")
private val UnorderedListPattern = Regex("""^(\s*)[-*+]\s+(.+)$""")
private val OrderedListPattern = Regex("""^(\s*)(\d+)[.)]\s+(.+)$""")
private val QuotePattern = Regex("""^\s*>\s?(.*)$""")
private val InlineLinkPattern = Regex("""^\[([^\]\n]+)]\((https?://[^\s)]+)\)""", RegexOption.IGNORE_CASE)
