package app.andy

/**
 * A DOM node the user picked in the embedded browser, plus the comment they typed
 * in the in-page annotation pill.
 */
data class BrowserElementAnnotation(
    val comment: String,
    val tag: String,
    val selector: String,
    val url: String,
    val pageTitle: String,
    val width: Int,
    val height: Int,
    val color: String,
    val font: String,
    val innerText: String,
    val imagePath: String? = null,
)

sealed class BrowserElementAnnotateEvent {
    data object Cancelled : BrowserElementAnnotateEvent()
    data class Submitted(val annotation: BrowserElementAnnotation) : BrowserElementAnnotateEvent()
}

fun formatBrowserElementAnnotation(annotation: BrowserElementAnnotation): String = buildString {
    val comment = annotation.comment.trim()
    if (comment.isNotEmpty()) {
        appendLine(comment)
        appendLine()
    }
    appendLine("[Browser element]")
    if (annotation.url.isNotBlank()) appendLine("URL: ${annotation.url}")
    if (annotation.pageTitle.isNotBlank()) appendLine("Page: ${annotation.pageTitle}")
    val tag = annotation.tag.ifBlank { "element" }
    append("Element: <").append(tag).append('>')
    if (annotation.selector.isNotBlank() && annotation.selector != tag) {
        append(" (").append(annotation.selector).append(')')
    }
    appendLine()
    if (annotation.width > 0 && annotation.height > 0) {
        appendLine("Size: ${annotation.width}×${annotation.height}")
    }
    if (annotation.color.isNotBlank()) appendLine("color: ${annotation.color}")
    if (annotation.font.isNotBlank()) appendLine("font: ${annotation.font}")
    val snippet = annotation.innerText.trim().replace("\n", " ").take(240)
    if (snippet.isNotEmpty()) appendLine("Text: $snippet")
}

/** Inject or tear down the in-page element inspector overlay. No-op off desktop/macOS. */
expect fun setBrowserElementInspectEnabled(enabled: Boolean)

/**
 * Subscribe to inspect cancel/submit events from WKWebView. The returned lambda
 * unregisters. No-op off desktop/macOS.
 */
expect fun observeBrowserElementAnnotations(
    onEvent: (BrowserElementAnnotateEvent) -> Unit,
): () -> Unit
