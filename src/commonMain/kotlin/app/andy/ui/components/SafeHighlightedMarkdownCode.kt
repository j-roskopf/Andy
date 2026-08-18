package app.andy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.andy.generated.resources.Res
import app.andy.andy.generated.resources.markdown_copy
import app.andy.rememberCopyText
import app.andy.ui.theme.AndyStroke
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import org.intellij.markdown.ast.ASTNode
import org.jetbrains.compose.resources.painterResource

/**
 * Syntax-highlighted fenced code with range validation. The Highlights dependency can return
 * spans that exceed the source length (notably while chat markdown is streaming), which crashes
 * Compose text layout — so invalid spans are dropped and plain code is shown instead.
 */
@Composable
internal fun SafeMarkdownHighlightedCodeFence(
    content: String,
    node: ASTNode,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    MarkdownCodeFence(content, node, style) { code, language, codeStyle ->
        if (isMermaidFenceLanguage(language)) {
            MermaidFence(
                code = code,
                language = language,
                style = codeStyle,
                highlightsBuilder = highlightsBuilder,
                showHeader = showHeader,
            )
        } else {
            SafeMarkdownHighlightedCode(
                code = code,
                language = language,
                style = codeStyle,
                highlightsBuilder = highlightsBuilder,
                showHeader = showHeader,
            )
        }
    }
}

@Composable
internal fun SafeMarkdownHighlightedCodeBlock(
    content: String,
    node: ASTNode,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    MarkdownCodeBlock(content, node, style) { code, language, codeStyle ->
        if (isMermaidFenceLanguage(language)) {
            MermaidFence(
                code = code,
                language = language,
                style = codeStyle,
                highlightsBuilder = highlightsBuilder,
                showHeader = showHeader,
            )
        } else {
            SafeMarkdownHighlightedCode(
                code = code,
                language = language,
                style = codeStyle,
                highlightsBuilder = highlightsBuilder,
                showHeader = showHeader,
            )
        }
    }
}

@Composable
internal fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val codeBackgroundCornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock
    val highlighted = remember(code, language, highlightsBuilder) {
        buildSafeHighlightedAnnotatedString(code, language, highlightsBuilder)
    }

    // Own header (not the library's): multiplatform-markdown-renderer copies via the
    // deprecated LocalClipboardManager API, which is a no-op on desktop Compose.
    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = false,
        language = language,
        code = code,
    ) {
        Column {
            if (showHeader) {
                AndyMarkdownCodeTopBar(language = language, code = code)
                HorizontalDivider(
                    thickness = AndyStroke.Hairline,
                    color = LocalMarkdownColors.current.dividerColor.copy(alpha = 0.3f),
                )
            }
            MarkdownBasicText(
                text = highlighted,
                style = style,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(codeBlockPadding),
            )
        }
    }
}

@Composable
private fun AndyMarkdownCodeTopBar(
    language: String?,
    code: String,
) {
    val copyText = rememberCopyText()
    val textColor = LocalMarkdownColors.current.text
    val languageLabel = language?.takeIf { it.isNotBlank() }?.uppercase() ?: "CODE"
    val iconTint = textColor.copy(alpha = 0.65f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = languageLabel,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor.copy(alpha = 0.6f),
        )
        Image(
            painter = painterResource(Res.drawable.markdown_copy),
            contentDescription = "Copy code",
            colorFilter = ColorFilter.tint(iconTint),
            modifier = Modifier
                .size(24.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { role = Role.Button }
                .clickable(onClickLabel = "Copy code") { copyText(code) }
                .padding(4.dp),
        )
    }
}

internal fun buildSafeHighlightedAnnotatedString(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")

    val syntaxLanguage = language?.let(SyntaxLanguage::getByName)
    val highlights = runCatching {
        highlightsBuilder
            .code(code)
            .let { builder -> if (syntaxLanguage != null) builder.language(syntaxLanguage) else builder }
            .build()
            .getHighlights()
    }.getOrNull().orEmpty()

    return buildAnnotatedString {
        append(code)
        for (highlight in highlights) {
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || end <= start || end > code.length) continue
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(spanStyle, start, end)
        }
    }
}
