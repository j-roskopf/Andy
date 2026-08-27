package app.andy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import dev.snipme.highlights.Highlights

@Composable
actual fun MermaidFence(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    SafeMarkdownHighlightedCode(
        code = code,
        language = language,
        style = style,
        highlightsBuilder = highlightsBuilder,
        showHeader = showHeader,
    )
}
