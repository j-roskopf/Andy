package app.andy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
            val markdownState = rememberMarkdownState(text, retainState = true)
            val body = MaterialTheme.typography.bodyMedium.copy(fontFamily = DisplayFont, fontSize = 13.sp, lineHeight = 20.sp)
            Markdown(
                markdownState = markdownState,
                colors = markdownColor(
                    text = TextPrimary,
                    codeBackground = AndyColors.Neutral850,
                    inlineCodeBackground = AndyColors.Neutral700,
                    dividerColor = Border,
                    tableBackground = AndyColors.Neutral850,
                ),
                typography = markdownTypography(
                    h1 = MaterialTheme.typography.displayLarge.copy(fontFamily = DisplayFont, fontSize = 26.sp, lineHeight = 32.sp),
                    h2 = MaterialTheme.typography.headlineLarge.copy(fontFamily = DisplayFont, fontSize = 22.sp, lineHeight = 28.sp),
                    h3 = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFont, fontSize = 18.sp, lineHeight = 24.sp),
                    h4 = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFont, fontSize = 16.sp, lineHeight = 22.sp),
                    h5 = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFont, fontSize = 14.sp, lineHeight = 20.sp),
                    h6 = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFont, fontSize = 13.sp, lineHeight = 18.sp),
                    text = body,
                    paragraph = body,
                    quote = body.copy(fontSize = 13.sp, lineHeight = 19.sp),
                    code = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 18.sp),
                    inlineCode = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.sp, color = Rust),
                    ordered = body,
                    bullet = body,
                    list = body,
                    textLink = TextLinkStyles(
                        style = body.copy(color = Cyan, textDecoration = TextDecoration.Underline).toSpanStyle(),
                    ),
                ),
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
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AndySpace.S4),
            )
        }
    }
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
