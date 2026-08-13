package app.andy.ui.agents

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val COMPOSER_SLASH_TOKEN = Regex("""(?:^|\s)(/([A-Za-z0-9:_-]+))(?=\s|$)""")
private val COMPOSER_MENTION_TOKEN = Regex("""(?:^|\s)(@(\S+))(?=\s|$)""")

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
}

internal fun composerSlashTokenTransformation(
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
    mentionColor: Color? = null,
): VisualTransformation {
    if (skillNames.isEmpty() && commandNames.isEmpty() && mentionColor == null) return VisualTransformation.None
    return VisualTransformation { text ->
        TransformedText(
            text = annotateComposerSlashTokens(
                text = text.text,
                skillNames = skillNames,
                commandNames = commandNames,
                skillColor = skillColor,
                commandColor = commandColor,
                mentionColor = mentionColor,
            ),
            offsetMapping = OffsetMapping.Identity,
        )
    }
}
