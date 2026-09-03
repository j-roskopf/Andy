package app.andy.ui.agents

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

private val COMPOSER_SLASH_TOKEN = Regex("""(?:^|\s)(/([A-Za-z0-9:_-]+))(?=\s|$)""")
private val COMPOSER_MENTION_TOKEN = Regex("""(?:^|\s)(@(\S+))(?=\s|$)""")

private val COMPOSER_INLINE_CODE = Regex("""`([^`\n]+)`""")
private val COMPOSER_MD_LINK = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)""")
private val COMPOSER_AUTOLINK = Regex("""https?://[^\s<>\]]+""", RegexOption.IGNORE_CASE)
private val COMPOSER_BOLD = Regex("""\*\*([^*\n]+)\*\*""")
/** Single-asterisk emphasis; rejects whitespace-flanking so `**bold**, *italic*` stays two spans. */
private val COMPOSER_ITALIC = Regex("""\*(?!\s)([^*\n]+?)(?<!\s)\*""")
private val COMPOSER_STRIKE = Regex("""~~([^~\n]+)~~""")

/** Colors / type treatments for inline markdown in the composer field. */
internal data class ComposerMarkdownStyles(
    val linkColor: Color,
    val codeColor: Color,
    val codeBackground: Color,
    val codeFontFamily: FontFamily = FontFamily.Monospace,
)

/**
 * Tints recognized `/skill` and `/command` tokens, plus `@file` mentions, in
 * composer prompts so they read as chips-in-text rather than plain mono body
 * copy. Mentions are tinted on syntax alone (no index lookup here), since
 * arbitrary project paths can't be validated against a known-name set cheaply.
 */
internal fun annotateComposerSlashTokens(
    text: String,
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
    mentionColor: Color? = null,
): AnnotatedString {
    if (text.isEmpty() || (skillNames.isEmpty() && commandNames.isEmpty() && mentionColor == null)) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        append(text)
        applyComposerSlashStyles(
            text = text,
            skillNames = skillNames,
            commandNames = commandNames,
            skillColor = skillColor,
            commandColor = commandColor,
            mentionColor = mentionColor,
        )
    }
}

/**
 * Styles common inline markdown in the composer while keeping source text
 * (and cursor offsets) unchanged: autolinks, `[label](url)`, `` `code` ``,
 * `**bold**`, `*italic*`, and `~~strike~~`.
 */
internal fun annotateComposerMarkdown(
    text: String,
    styles: ComposerMarkdownStyles,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        applyComposerMarkdownStyles(text, styles)
    }
}

/**
 * Combined slash/mention + markdown styling for the chat composer text field.
 */
internal fun annotateComposerPrompt(
    text: String,
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
    mentionColor: Color? = null,
    markdown: ComposerMarkdownStyles? = null,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    val hasSlash = skillNames.isNotEmpty() || commandNames.isNotEmpty() || mentionColor != null
    if (!hasSlash && markdown == null) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        if (markdown != null) {
            applyComposerMarkdownStyles(text, markdown)
        }
        if (hasSlash) {
            applyComposerSlashStyles(
                text = text,
                skillNames = skillNames,
                commandNames = commandNames,
                skillColor = skillColor,
                commandColor = commandColor,
                mentionColor = mentionColor,
            )
        }
    }
}

internal fun composerPromptTransformation(
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
    mentionColor: Color? = null,
    markdown: ComposerMarkdownStyles? = null,
): VisualTransformation {
    val hasSlash = skillNames.isNotEmpty() || commandNames.isNotEmpty() || mentionColor != null
    if (!hasSlash && markdown == null) return VisualTransformation.None
    return VisualTransformation { text ->
        TransformedText(
            text = annotateComposerPrompt(
                text = text.text,
                skillNames = skillNames,
                commandNames = commandNames,
                skillColor = skillColor,
                commandColor = commandColor,
                mentionColor = mentionColor,
                markdown = markdown,
            ),
            offsetMapping = OffsetMapping.Identity,
        )
    }
}

internal fun composerSlashTokenTransformation(
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
    mentionColor: Color? = null,
    markdown: ComposerMarkdownStyles? = null,
): VisualTransformation = composerPromptTransformation(
    skillNames = skillNames,
    commandNames = commandNames,
    skillColor = skillColor,
    commandColor = commandColor,
    mentionColor = mentionColor,
    markdown = markdown,
)

private fun AnnotatedString.Builder.applyComposerSlashStyles(
    text: String,
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
    mentionColor: Color?,
) {
    COMPOSER_SLASH_TOKEN.findAll(text).forEach { match ->
        val token = match.groupValues[1]
        if (token.isEmpty()) return@forEach
        val name = match.groupValues[2]
        val color = when {
            name in commandNames -> commandColor
            name in skillNames -> skillColor
            else -> return@forEach
        }
        // MatchGroup.range is JVM-only; the token always ends the match, so
        // derive its offsets from the full match range instead.
        val end = match.range.last + 1
        addStyle(
            SpanStyle(
                color = color,
                background = color.copy(alpha = 0.16f),
            ),
            start = end - token.length,
            end = end,
        )
    }
    if (mentionColor != null) {
        COMPOSER_MENTION_TOKEN.findAll(text).forEach { match ->
            val token = match.groupValues[1]
            if (token.isEmpty()) return@forEach
            val end = match.range.last + 1
            addStyle(
                SpanStyle(
                    color = mentionColor,
                    background = mentionColor.copy(alpha = 0.16f),
                ),
                start = end - token.length,
                end = end,
            )
        }
    }
}

private fun AnnotatedString.Builder.applyComposerMarkdownStyles(
    text: String,
    styles: ComposerMarkdownStyles,
) {
    val occupied = BooleanArray(text.length)

    fun free(start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || start >= end) return false
        for (i in start until end) if (occupied[i]) return false
        return true
    }

    fun mark(start: Int, end: Int) {
        for (i in start until end) occupied[i] = true
    }

    fun styleRange(start: Int, end: Int, style: SpanStyle) {
        if (!free(start, end)) return
        addStyle(style, start, end)
        mark(start, end)
    }

    // Fence code first so emphasis / links inside backticks stay plain.
    COMPOSER_INLINE_CODE.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        styleRange(
            start,
            end,
            SpanStyle(
                color = styles.codeColor,
                background = styles.codeBackground,
                fontFamily = styles.codeFontFamily,
            ),
        )
    }

    COMPOSER_MD_LINK.findAll(text).forEach { match ->
        val fullStart = match.range.first
        val fullEnd = match.range.last + 1
        if (!free(fullStart, fullEnd)) return@forEach
        val label = match.groupValues[1]
        val labelStart = fullStart + 1 // after '['
        val labelEnd = labelStart + label.length
        // Dim the `[` `](url)` chrome; accent the visible label.
        addStyle(SpanStyle(color = styles.linkColor.copy(alpha = 0.55f)), fullStart, labelStart)
        addStyle(
            SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
            labelStart,
            labelEnd,
        )
        addStyle(SpanStyle(color = styles.linkColor.copy(alpha = 0.55f)), labelEnd, fullEnd)
        mark(fullStart, fullEnd)
    }

    COMPOSER_AUTOLINK.findAll(text).forEach { match ->
        val rawStart = match.range.first
        val raw = match.value
        val trimmedLen = trimAutolinkLength(raw)
        if (trimmedLen <= 0) return@forEach
        val end = rawStart + trimmedLen
        styleRange(
            rawStart,
            end,
            SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline),
        )
    }

    COMPOSER_BOLD.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        styleRange(start, end, SpanStyle(fontWeight = FontWeight.SemiBold))
    }

    COMPOSER_ITALIC.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        // Skip spans already claimed by bold (`**...**`).
        if (start > 0 && text[start - 1] == '*') return@forEach
        if (end < text.length && text[end] == '*') return@forEach
        styleRange(start, end, SpanStyle(fontStyle = FontStyle.Italic))
    }

    COMPOSER_STRIKE.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        styleRange(start, end, SpanStyle(textDecoration = TextDecoration.LineThrough))
    }
}

/** Drop trailing sentence punctuation commonly glued onto pasted URLs. */
internal fun trimAutolinkLength(raw: String): Int {
    var end = raw.length
    while (end > 0 && raw[end - 1] in ".,;:!?)]}>\"'") end--
    return end
}

/**
 * Clickable span for a composer link. [start]/[end] covers the hit target in the
 * raw field text (label for markdown links; the URL for autolinks).
 */
internal data class ComposerLink(
    val start: Int,
    val end: Int,
    val url: String,
)

/** Markdown `[label](url)` labels and bare `http(s)://…` autolinks, skipping inline code. */
internal fun findComposerLinks(text: String): List<ComposerLink> {
    if (text.isEmpty()) return emptyList()
    val occupied = BooleanArray(text.length)
    fun mark(start: Int, end: Int) {
        for (i in start until end) occupied[i] = true
    }
    fun free(start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || start >= end) return false
        for (i in start until end) if (occupied[i]) return false
        return true
    }

    COMPOSER_INLINE_CODE.findAll(text).forEach { match ->
        mark(match.range.first, match.range.last + 1)
    }

    val links = ArrayList<ComposerLink>()
    COMPOSER_MD_LINK.findAll(text).forEach { match ->
        val fullStart = match.range.first
        val fullEnd = match.range.last + 1
        if (!free(fullStart, fullEnd)) return@forEach
        val label = match.groupValues[1]
        val url = match.groupValues[2]
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            mark(fullStart, fullEnd)
            return@forEach
        }
        val labelStart = fullStart + 1
        val labelEnd = labelStart + label.length
        links += ComposerLink(labelStart, labelEnd, url)
        mark(fullStart, fullEnd)
    }
    COMPOSER_AUTOLINK.findAll(text).forEach { match ->
        val rawStart = match.range.first
        val trimmedLen = trimAutolinkLength(match.value)
        if (trimmedLen <= 0) return@forEach
        val end = rawStart + trimmedLen
        if (!free(rawStart, end)) return@forEach
        links += ComposerLink(rawStart, end, match.value.take(trimmedLen))
        mark(rawStart, end)
    }
    return links.sortedBy { it.start }
}

internal fun composerLinkAt(text: String, offset: Int): ComposerLink? {
    if (offset < 0 || offset >= text.length) return null
    return findComposerLinks(text).firstOrNull { offset in it.start until it.end }
}

