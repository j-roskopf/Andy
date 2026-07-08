package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Style
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

@Composable
actual fun HostCodeEditor(
    path: String,
    text: String,
    languageHint: String,
    modifier: Modifier,
    onTextChange: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit,
    onSearchAll: () -> Unit,
    onSearchNames: () -> Unit,
    onSearchContents: () -> Unit,
) {
    SwingPanel(
        modifier = modifier,
        background = Color(0xFF11100D),
        factory = {
            HostCodeEditorPanel(onTextChange, onSave, onClose, onSearchAll, onSearchNames, onSearchContents)
        },
        update = { panel ->
            panel.updateDocument(path, text)
            panel.updateLanguage(languageHint)
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
    private val editorBackground = java.awt.Color(0x11100D)
    private val gutterBackground = java.awt.Color(0x171511)
    private val gutterBorder = java.awt.Color(0x302D27)
    private val primaryText = java.awt.Color(0xE4DED0)
    private val secondaryText = java.awt.Color(0x8E8779)
    private val rust = java.awt.Color(0xD18A4B)
    private val green = java.awt.Color(0x94C17A)
    private val cyan = java.awt.Color(0x88AFC8)
    private val yellow = java.awt.Color(0xE3B05E)
    private val red = java.awt.Color(0xE26F5C)
    private val editor = RSyntaxTextArea().apply {
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
        isCodeFoldingEnabled = true
        antiAliasingEnabled = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        background = editorBackground
        currentLineHighlightColor = java.awt.Color(0x1D1A16)
        foreground = primaryText
        caretColor = rust
        selectionColor = java.awt.Color(0x514D44)
        selectedTextColor = java.awt.Color(0xF4F1E8)
        markOccurrences = true
        markOccurrencesColor = java.awt.Color(0x2F2A22)
        paintMarkOccurrencesBorder = false
        markAllHighlightColor = java.awt.Color(0x3A3022)
        markAllOnOccurrenceSearches = false
        matchedBracketBGColor = java.awt.Color(0x2C2117)
        matchedBracketBorderColor = rust
        tabLineColor = java.awt.Color(0x302D27)
        hyperlinkForeground = cyan
        highlightSecondaryLanguages = false
        syntaxScheme = themedSyntaxScheme(syntaxScheme)
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                if (!programmaticUpdate) onTextChange(currentPath, text)
            }
        })
    }

    init {
        val scrollPane = RTextScrollPane(editor).apply {
            lineNumbersEnabled = true
            background = editorBackground
            viewport.background = editorBackground
            gutter.background = gutterBackground
            gutter.lineNumberColor = secondaryText
            gutter.currentLineNumberColor = rust
            gutter.borderColor = gutterBorder
            gutter.foldIndicatorForeground = secondaryText
            gutter.foldIndicatorArmedForeground = rust
            gutter.foldBackground = gutterBackground
            border = null
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.applyAndyTheme()
            horizontalScrollBar.applyAndyTheme()
        }
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

    private fun themedSyntaxScheme(base: org.fife.ui.rsyntaxtextarea.SyntaxScheme): org.fife.ui.rsyntaxtextarea.SyntaxScheme {
        val scheme = base.clone() as org.fife.ui.rsyntaxtextarea.SyntaxScheme
        fun style(token: Int, color: java.awt.Color, bold: Boolean = false) {
            scheme.setStyle(token, Style(color, null, if (bold) font.deriveFont(Font.BOLD) else font))
        }
        style(TokenTypes.IDENTIFIER, primaryText)
        style(TokenTypes.RESERVED_WORD, rust, bold = true)
        style(TokenTypes.RESERVED_WORD_2, rust)
        style(TokenTypes.FUNCTION, cyan)
        style(TokenTypes.LITERAL_BOOLEAN, java.awt.Color(0xB865FF), bold = true)
        style(TokenTypes.LITERAL_NUMBER_DECIMAL_INT, yellow)
        style(TokenTypes.LITERAL_NUMBER_FLOAT, yellow)
        style(TokenTypes.LITERAL_NUMBER_HEXADECIMAL, yellow)
        style(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, green)
        style(TokenTypes.LITERAL_CHAR, green)
        style(TokenTypes.DATA_TYPE, cyan)
        style(TokenTypes.VARIABLE, primaryText)
        style(TokenTypes.OPERATOR, red)
        style(TokenTypes.SEPARATOR, red)
        style(TokenTypes.COMMENT_EOL, secondaryText)
        style(TokenTypes.COMMENT_MULTILINE, secondaryText)
        style(TokenTypes.COMMENT_DOCUMENTATION, secondaryText)
        style(TokenTypes.MARKUP_TAG_NAME, rust)
        style(TokenTypes.MARKUP_TAG_ATTRIBUTE, cyan)
        style(TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, green)
        return scheme
    }
}

private fun JScrollBar.applyAndyTheme() {
    unitIncrement = 16
    blockIncrement = 96
    preferredSize = if (orientation == JScrollBar.VERTICAL) Dimension(12, 0) else Dimension(0, 12)
    background = java.awt.Color(0x11100D)
    ui = AndyScrollBarUi()
}

private class AndyScrollBarUi : BasicScrollBarUI() {
    private val track = java.awt.Color(0x11100D)
    private val thumb = java.awt.Color(0x514D44)
    private val thumbHover = java.awt.Color(0x8D6746)

    override fun configureScrollBarColors() {
        trackColor = track
        thumbColor = thumb
    }

    override fun createDecreaseButton(orientation: Int): JButton = invisibleButton()

    override fun createIncreaseButton(orientation: Int): JButton = invisibleButton()

    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) {
        g.color = track
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height)
    }

    override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: Rectangle) {
        if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
        val g2 = g.create() as java.awt.Graphics2D
        g2.color = if (isThumbRollover) thumbHover else thumb
        g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2, thumbBounds.width - 4, thumbBounds.height - 4, 8, 8)
        g2.dispose()
    }

    private fun invisibleButton(): JButton = JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
        isOpaque = false
        isBorderPainted = false
        isFocusable = false
    }
}
