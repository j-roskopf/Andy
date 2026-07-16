package app.andy.ui.agents

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val COMPOSER_SLASH_TOKEN = Regex("""(?:^|\s)(/([A-Za-z0-9:_-]+))(?=\s|$)""")

/**
 * Tints recognized `/skill` and `/command` tokens in composer prompts so they
 * read as chips-in-text rather than plain mono body copy.
 */
internal fun annotateComposerSlashTokens(
    text: String,
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
): AnnotatedString {
    if (text.isEmpty() || (skillNames.isEmpty() && commandNames.isEmpty())) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        append(text)
        COMPOSER_SLASH_TOKEN.findAll(text).forEach { match ->
            val token = match.groups[1] ?: return@forEach
            val name = match.groupValues[2]
            val color = when {
                name in commandNames -> commandColor
                name in skillNames -> skillColor
                else -> return@forEach
            }
            addStyle(
                SpanStyle(
                    color = color,
                    background = color.copy(alpha = 0.16f),
                ),
                start = token.range.first,
                end = token.range.last + 1,
            )
        }
    }
}

internal fun composerSlashTokenTransformation(
    skillNames: Set<String>,
    commandNames: Set<String>,
    skillColor: Color,
    commandColor: Color,
): VisualTransformation {
    if (skillNames.isEmpty() && commandNames.isEmpty()) return VisualTransformation.None
    return VisualTransformation { text ->
        TransformedText(
            text = annotateComposerSlashTokens(
                text = text.text,
                skillNames = skillNames,
                commandNames = commandNames,
                skillColor = skillColor,
                commandColor = commandColor,
            ),
            offsetMapping = OffsetMapping.Identity,
        )
    }
}
