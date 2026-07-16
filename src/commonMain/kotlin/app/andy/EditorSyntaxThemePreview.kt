package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.andy.model.EditorSyntaxTheme

internal val EditorSyntaxThemeSample = """
// Settings preview
fun greet(name: String): Int {
    val message = "hello, ${'$'}name"
    return if (name.isNotBlank()) message.length * 2 else 0
}
""".trimIndent()

@Composable
internal expect fun EditorSyntaxThemePreview(
    syntaxThemeId: String = EditorSyntaxTheme.Andy.id,
    modifier: Modifier = Modifier,
)
