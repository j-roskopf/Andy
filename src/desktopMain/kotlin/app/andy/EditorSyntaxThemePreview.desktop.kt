package app.andy

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.andy.model.EditorSyntaxTheme
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

@Composable
internal actual fun EditorSyntaxThemePreview(
    syntaxThemeId: String,
    modifier: Modifier,
) {
    val panelBackground = remember(syntaxThemeId) { editorPanelBackground(syntaxThemeId) }
    SwingPanel(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3)),
        background = panelBackground,
        factory = { EditorSyntaxThemePreviewPanel() },
        update = { panel -> panel.updateTheme(syntaxThemeId) },
    )
}

private class EditorSyntaxThemePreviewPanel : JPanel(BorderLayout()) {
    private var currentThemeId = ""
    private val editor = RSyntaxTextArea(EditorSyntaxThemeSample).apply {
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_KOTLIN
        isCodeFoldingEnabled = false
        antiAliasingEnabled = true
        isEditable = false
        isEnabled = false
        highlightCurrentLine = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = EmptyBorder(8, 10, 8, 10)
        markOccurrences = false
        highlightSecondaryLanguages = false
        caret.isVisible = false
        caret.isSelectionVisible = false
    }
    private val scrollPane = RTextScrollPane(editor).apply {
        lineNumbersEnabled = true
        border = null
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        add(scrollPane, BorderLayout.CENTER)
        updateTheme(EditorSyntaxTheme.Andy.id)
    }

    fun updateTheme(syntaxThemeId: String) {
        val themeId = EditorSyntaxTheme.fromId(syntaxThemeId).id
        if (currentThemeId == themeId) return
        currentThemeId = themeId
        applyEditorSyntaxTheme(editor, scrollPane, themeId)
        editor.isEditable = false
        editor.isEnabled = false
        editor.highlightCurrentLine = false
        editor.caret.isVisible = false
        revalidate()
        repaint()
    }
}
