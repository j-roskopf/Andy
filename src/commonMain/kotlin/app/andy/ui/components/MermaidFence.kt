package app.andy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import dev.snipme.highlights.Highlights

/** True when a fenced code info-string should render as a Mermaid diagram. */
internal fun isMermaidFenceLanguage(language: String?): Boolean {
    val token = language
        ?.trim()
        ?.substringBefore(' ')
        ?.substringBefore('{')
        ?.substringBefore(',')
        ?.lowercase()
        .orEmpty()
    return token == "mermaid" || token == "mmd" || token == "mermaid-js"
}

/** Best-effort width/height from an SVG document. Used to size chat previews and fit-to-window. */
internal fun mermaidSvgIntrinsicSize(svg: String, fallbackWidth: Float = 800f, fallbackHeight: Float = 400f): Pair<Float, Float> {
    val viewBox = ViewBoxPattern.find(svg)
    if (viewBox != null) {
        val width = viewBox.groupValues[3].toFloatOrNull()
        val height = viewBox.groupValues[4].toFloatOrNull()
        if (width != null && height != null && width > 0f && height > 0f) {
            return width to height
        }
    }
    val width = svgLength(WidthPattern, svg)
    val height = svgLength(HeightPattern, svg)
    if (width != null && height != null && width > 0f && height > 0f) {
        return width to height
    }
    return fallbackWidth to fallbackHeight
}

private val ViewBoxPattern = Regex(
    """viewBox\s*=\s*["']\s*([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)\s+([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)\s+([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)\s+([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)""",
    RegexOption.IGNORE_CASE,
)
private val WidthPattern = Regex("""<svg\b[^>]*\bwidth\s*=\s*["']\s*([0-9.]+)""", RegexOption.IGNORE_CASE)
private val HeightPattern = Regex("""<svg\b[^>]*\bheight\s*=\s*["']\s*([0-9.]+)""", RegexOption.IGNORE_CASE)

private fun svgLength(pattern: Regex, svg: String): Float? =
    pattern.find(svg)?.groupValues?.getOrNull(1)?.toFloatOrNull()

@Composable
internal expect fun MermaidFence(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
)
