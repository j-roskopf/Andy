package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import app.andy.model.EditorSyntaxTheme
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@Composable
actual fun HostCodeEditor(
    path: String,
    text: String,
    languageHint: String,
    modifier: Modifier,
    syntaxThemeId: String,
    onTextChange: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit,
    onSearchAll: () -> Unit,
    onSearchNames: () -> Unit,
    onSearchContents: () -> Unit,
) {
    val panelBackground = remember(syntaxThemeId) { editorPanelBackground(syntaxThemeId) }
    SwingPanel(
        modifier = modifier,
        background = panelBackground,
        factory = {
            HostCodeEditorPanel(onTextChange, onSave, onClose, onSearchAll, onSearchNames, onSearchContents)
        },
        update = { panel ->
            panel.updateDocument(path, text)
            panel.updateLanguage(languageHint)
            panel.updateSyntaxTheme(syntaxThemeId)
            panel.onTextChange = onTextChange
            panel.onSave = onSave
            panel.onClose = onClose
            panel.onSearchAll = onSearchAll
            panel.onSearchNames = onSearchNames
            panel.onSearchContents = onSearchContents
        },
    )
}

private class HostCodeEditorPanel(
    var onTextChange: (String, String) -> Unit,
    var onSave: (String, String) -> Unit,
    var onClose: () -> Unit,
    var onSearchAll: () -> Unit,
    var onSearchNames: () -> Unit,
    var onSearchContents: () -> Unit,
) : JPanel(BorderLayout()) {
    private var programmaticUpdate = false
    private var currentPath = ""
    private var currentSyntaxThemeId = EditorSyntaxTheme.Andy.id
    private val editor = RSyntaxTextArea().apply {
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
        isCodeFoldingEnabled = true
        antiAliasingEnabled = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        markOccurrences = true
        paintMarkOccurrencesBorder = false
        markAllOnOccurrenceSearches = false
        highlightSecondaryLanguages = false
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                if (!programmaticUpdate) onTextChange(currentPath, text)
            }
        })
    }
    private val scrollPane = RTextScrollPane(editor).apply {
        lineNumbersEnabled = true
        border = null
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    init {
        applyEditorSyntaxTheme(editor, scrollPane, currentSyntaxThemeId)
        add(scrollPane, BorderLayout.CENTER)
        bindShortcut("save", KeyEvent.VK_S) { onSave(currentPath, editor.text) }
        bindShortcut("close", KeyEvent.VK_W, action = onClose)
        bindShortcut("find", KeyEvent.VK_F) { findInFile() }
        bindShortcut("searchAll", KeyEvent.VK_A, shift = true) { onSearchAll() }
        bindShortcut("searchNames", KeyEvent.VK_N, shift = true) { onSearchNames() }
        bindShortcut("searchContents", KeyEvent.VK_F, shift = true) { onSearchContents() }
    }

    fun updateDocument(path: String, value: String) {
        val pathChanged = currentPath != path
        currentPath = path
        if (!pathChanged && editor.text == value) return
        programmaticUpdate = true
        val caret = editor.caretPosition
        editor.text = value
        editor.caretPosition = if (pathChanged) 0 else caret.coerceAtMost(value.length)
        programmaticUpdate = false
    }

    fun updateLanguage(languageHint: String) {
        editor.syntaxEditingStyle = when (languageHint.lowercase()) {
            "kotlin" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN
            "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA
            "javascript" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT
            "typescript" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT
            "json" -> SyntaxConstants.SYNTAX_STYLE_JSON
            "xml" -> SyntaxConstants.SYNTAX_STYLE_XML
            "css" -> SyntaxConstants.SYNTAX_STYLE_CSS
            "markdown" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN
            "python" -> SyntaxConstants.SYNTAX_STYLE_PYTHON
            "ruby" -> SyntaxConstants.SYNTAX_STYLE_RUBY
            "rust" -> SyntaxConstants.SYNTAX_STYLE_RUST
            "go" -> SyntaxConstants.SYNTAX_STYLE_GO
            "c" -> SyntaxConstants.SYNTAX_STYLE_C
            "cpp" -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS
            "shell" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL
            "yaml" -> SyntaxConstants.SYNTAX_STYLE_YAML
            "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL
            "groovy" -> SyntaxConstants.SYNTAX_STYLE_GROOVY
            "gradle" -> SyntaxConstants.SYNTAX_STYLE_GROOVY
            "properties" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
            "ini" -> SyntaxConstants.SYNTAX_STYLE_INI
            "dockerfile" -> SyntaxConstants.SYNTAX_STYLE_DOCKERFILE
            "makefile" -> SyntaxConstants.SYNTAX_STYLE_MAKEFILE
            "csv" -> SyntaxConstants.SYNTAX_STYLE_CSV
            "html" -> SyntaxConstants.SYNTAX_STYLE_HTML
            else -> SyntaxConstants.SYNTAX_STYLE_NONE
        }
    }

    fun updateSyntaxTheme(syntaxThemeId: String) {
        val themeId = EditorSyntaxTheme.fromId(syntaxThemeId).id
        if (currentSyntaxThemeId == themeId) return
        currentSyntaxThemeId = themeId
        applyEditorSyntaxTheme(editor, scrollPane, themeId)
    }

    private fun bindShortcut(name: String, keyCode: Int, shift: Boolean = false, action: () -> Unit) {
        val baseMask = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        val mask = if (shift) baseMask or InputEvent.SHIFT_DOWN_MASK else baseMask
        editor.inputMap.put(KeyStroke.getKeyStroke(keyCode, mask), name)
        editor.actionMap.put(name, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                SwingUtilities.invokeLater(action)
            }
        })
    }

    private fun findInFile() {
        val query = JOptionPane.showInputDialog(this, "Find", editor.selectedText.orEmpty())?.takeIf { it.isNotBlank() } ?: return
        val context = SearchContext().apply {
            searchFor = query
            matchCase = false
            isRegularExpression = false
            searchForward = true
            wholeWord = false
        }
        SearchEngine.find(editor, context)
    }
}
