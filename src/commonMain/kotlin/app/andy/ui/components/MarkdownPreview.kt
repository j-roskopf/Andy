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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.Border
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Markdown preview for scratchpads and notes.
 * Uses [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer).
 * When [onTextChange] is provided, task checkboxes toggle `[ ]` / `[x]` in the source.
 */
@Composable
internal fun MarkdownPreview(
    text: String,
    modifier: Modifier = Modifier,
    onTextChange: ((String) -> Unit)? = null,
) {
    Box(
        modifier
            .background(Panel, RoundedCornerShape(AndyRadius.R2))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R2)),
    ) {
        if (text.isBlank()) {
            Text(
                "Nothing to preview yet.",
                color = TextSecondary,
                fontFamily = MonoFont,
                fontSize = 12.sp,
                modifier = Modifier.padding(AndySpace.S4),
            )
        } else {
            AndyMarkdown(
                text = text,
                density = AndyMarkdownDensity.Preview,
                onTextChange = onTextChange,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AndySpace.S4),
            )
        }
    }
}

/**
 * Full GFM markdown for agent chat bubbles. Keeps previous content visible while
 * streamed text is re-parsed, and avoids nested scrolling inside the transcript list.
 */
@Composable
internal fun ChatMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = 19.sp,
    density: AndyMarkdownDensity = AndyMarkdownDensity.Chat,
) {
    if (text.isBlank()) return
    AndyMarkdown(
        text = text,
        density = density,
        bodyLineHeight = lineHeight,
        modifier = modifier.fillMaxWidth(),
    )
}

internal enum class AndyMarkdownDensity {
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
) {
    val markdownState = rememberMarkdownState(text, retainState = true)
    val thinking = density == AndyMarkdownDensity.Thinking
    val body = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = if (thinking) MonoFont else DisplayFont,
        fontSize = if (thinking) 11.sp else 13.sp,
        lineHeight = bodyLineHeight,
        color = if (thinking) TextSecondary else TextPrimary,
    )
    val headingScale = when (density) {
        AndyMarkdownDensity.Preview -> 1f
        AndyMarkdownDensity.Chat -> 0.82f
        AndyMarkdownDensity.Thinking -> 0.7f
    }
    Markdown(
        markdownState = markdownState,
        colors = markdownColor(
            text = if (thinking) TextSecondary else TextPrimary,
            codeBackground = if (thinking) AndyColors.Neutral850.copy(alpha = 0.35f) else AndyColors.Neutral850,
            inlineCodeBackground = if (thinking) Cyan.copy(alpha = 0.12f) else AndyColors.Neutral700,
            dividerColor = Border.copy(alpha = if (thinking) 0.35f else 1f),
            tableBackground = AndyColors.Neutral850.copy(alpha = if (thinking) 0.4f else 1f),
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
                color = if (thinking) TextSecondary else TextPrimary,
            ),
            inlineCode = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFont,
                fontSize = if (thinking) 10.sp else 12.sp,
                color = if (thinking) Cyan.copy(alpha = 0.88f) else Rust,
            ),
            ordered = body,
            bullet = body,
            list = body,
            textLink = TextLinkStyles(
                style = body.copy(color = Cyan.copy(alpha = if (thinking) 0.85f else 1f), textDecoration = TextDecoration.Underline).toSpanStyle(),
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
        components = markdownComponents(
            checkbox = { model ->
                MarkdownCheckBox(
                    content = model.content,
                    node = model.node,
                    style = model.typography.text,
                    checkedIndicator = { checked, checkboxModifier ->
                        // Material's default 48dp touch target sits below the list text baseline.
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                            Checkbox(
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
        ),
        modifier = modifier,
    )
}

/** Toggle a GFM task marker at [startOffset], [endOffset] inside [content]. */
internal fun toggleMarkdownCheckbox(
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
