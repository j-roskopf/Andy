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
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicButtonUI

@Composable
actual fun HostCodeEditor(
    path: String,
    text: String,
    languageHint: String,
    modifier: Modifier,
    syntaxThemeId: String,
    initialLine: Int?,
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
            panel.navigateToLineOnce(path, initialLine)
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
    private var lastNavigatedKey: Pair<String, Int>? = null
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
    private val findBar = FindBar(
        onFind = { forward -> findInFile(forward) },
        onClose = { hideFindBar() },
        onQueryChanged = { findInFile(forward = true, fromTyping = true) },
    )

    init {
        applyEditorSyntaxTheme(editor, scrollPane, currentSyntaxThemeId)
        findBar.applyTheme(editorFindBarColors(currentSyntaxThemeId, editor))
        findBar.isVisible = false
        add(findBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        bindShortcut(editor, "save", KeyEvent.VK_S) { onSave(currentPath, editor.text) }
        bindShortcut(editor, "close", KeyEvent.VK_W, action = onClose)
        bindShortcut(editor, "find", KeyEvent.VK_F) { showFindBar() }
        bindShortcut(editor, "findNext", KeyEvent.VK_G) { findInFile(forward = true) }
        bindShortcut(editor, "findPrev", KeyEvent.VK_G, shift = true) { findInFile(forward = false) }
        bindShortcut(editor, "searchAll", KeyEvent.VK_A, shift = true) { onSearchAll() }
        bindShortcut(editor, "searchNames", KeyEvent.VK_N, shift = true) { onSearchNames() }
        bindShortcut(editor, "searchContents", KeyEvent.VK_F, shift = true) { onSearchContents() }
        bindShortcut(findBar.queryField, "find", KeyEvent.VK_F) { showFindBar() }
        bindShortcut(findBar.queryField, "findNext", KeyEvent.VK_G) { findInFile(forward = true) }
        bindShortcut(findBar.queryField, "findPrev", KeyEvent.VK_G, shift = true) { findInFile(forward = false) }
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
        if (findBar.isVisible && findBar.query.isNotBlank()) {
            findInFile(forward = true, fromTyping = true)
        }
    }

    /** Moves the caret to [line] (1-based) once per (path, line) pair, so recomposition doesn't fight manual scrolling. */
    fun navigateToLineOnce(path: String, line: Int?) {
        if (line == null) return
        val key = path to line
        if (lastNavigatedKey == key) return
        lastNavigatedKey = key
        runCatching {
            val offset = editor.getLineStartOffset((line - 1).coerceAtLeast(0).coerceAtMost(editor.lineCount - 1))
            editor.caretPosition = offset
            SwingUtilities.invokeLater { editor.requestFocusInWindow() }
        }
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
        findBar.applyTheme(editorFindBarColors(themeId, editor))
    }

    private fun showFindBar() {
        val selection = editor.selectedText.orEmpty()
        if (selection.isNotBlank() && !selection.contains('\n')) {
            findBar.query = selection
        }
        findBar.isVisible = true
        revalidate()
        SwingUtilities.invokeLater {
            findBar.focusField()
            if (findBar.query.isNotBlank()) findInFile(forward = true, fromTyping = true)
        }
    }

    private fun hideFindBar() {
        clearMarks()
        findBar.setStatus("")
        findBar.isVisible = false
        revalidate()
        SwingUtilities.invokeLater { editor.requestFocusInWindow() }
    }

    private fun findInFile(forward: Boolean, fromTyping: Boolean = false) {
        val query = findBar.query
        if (query.isBlank()) {
            clearMarks()
            findBar.setStatus("")
            return
        }
        if (fromTyping) {
            // Restart from the top so live typing always lands on the first match.
            editor.caretPosition = 0
        }
        val context = SearchContext(query).apply {
            matchCase = false
            isRegularExpression = false
            searchForward = forward
            searchWrap = true
            wholeWord = false
            markAll = true
        }
        val result = SearchEngine.find(editor, context)
        val marked = result.markedCount
        findBar.setStatus(
            when {
                !result.wasFound() && marked == 0 -> "No results"
                marked > 0 -> "$marked match${if (marked == 1) "" else "es"}"
                result.wasFound() -> "1 match"
                else -> ""
            },
        )
    }

    private fun clearMarks() {
        SearchEngine.markAll(editor, SearchContext(""))
    }

    private fun bindShortcut(component: JComponent, name: String, keyCode: Int, shift: Boolean = false, action: () -> Unit) {
        val baseMask = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
            InputEvent.META_DOWN_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK
        }
        val mask = if (shift) baseMask or InputEvent.SHIFT_DOWN_MASK else baseMask
        component.inputMap.put(KeyStroke.getKeyStroke(keyCode, mask), name)
        component.actionMap.put(
            name,
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    SwingUtilities.invokeLater(action)
                }
            },
        )
    }
}

private data class FindBarColors(
    val background: java.awt.Color,
    val foreground: java.awt.Color,
    val secondary: java.awt.Color,
    val fieldBackground: java.awt.Color,
    val fieldBorder: java.awt.Color,
    val fieldBorderFocused: java.awt.Color,
    val buttonHover: java.awt.Color,
)

private fun editorFindBarColors(syntaxThemeId: String, editor: RSyntaxTextArea): FindBarColors {
    return when (EditorSyntaxTheme.fromId(syntaxThemeId)) {
        EditorSyntaxTheme.Andy, EditorSyntaxTheme.Dark, EditorSyntaxTheme.Monokai, EditorSyntaxTheme.Druid -> FindBarColors(
            background = java.awt.Color(0x171511),
            foreground = java.awt.Color(0xE4DED0),
            secondary = java.awt.Color(0x8E8779),
            fieldBackground = java.awt.Color(0x11100D),
            fieldBorder = java.awt.Color(0x302D27),
            fieldBorderFocused = java.awt.Color(0xD18A4B),
            buttonHover = java.awt.Color(0x2C2117),
        )
        EditorSyntaxTheme.Idea -> FindBarColors(
            background = java.awt.Color(0x313335),
            foreground = java.awt.Color(0xA9B7C6),
            secondary = java.awt.Color(0x808080),
            fieldBackground = java.awt.Color(0x2B2B2B),
            fieldBorder = java.awt.Color(0x555555),
            fieldBorderFocused = java.awt.Color(0x6897BB),
            buttonHover = java.awt.Color(0x3C3F41),
        )
        else -> {
            val bg = editor.background ?: java.awt.Color.WHITE
            val fg = editor.foreground ?: java.awt.Color.BLACK
            val border = java.awt.Color(
                (bg.red * 0.85 + fg.red * 0.15).toInt().coerceIn(0, 255),
                (bg.green * 0.85 + fg.green * 0.15).toInt().coerceIn(0, 255),
                (bg.blue * 0.85 + fg.blue * 0.15).toInt().coerceIn(0, 255),
            )
            FindBarColors(
                background = blend(bg, fg, 0.06f),
                foreground = fg,
                secondary = blend(fg, bg, 0.35f),
                fieldBackground = bg,
                fieldBorder = border,
                fieldBorderFocused = editor.caretColor ?: java.awt.Color(0x3875D7),
                buttonHover = blend(bg, fg, 0.12f),
            )
        }
    }
}

private fun blend(a: java.awt.Color, b: java.awt.Color, amount: Float): java.awt.Color {
    val t = amount.coerceIn(0f, 1f)
    return java.awt.Color(
        (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255),
        (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255),
        (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255),
    )
}

private class FindBar(
    private val onFind: (forward: Boolean) -> Unit,
    private val onClose: () -> Unit,
    private val onQueryChanged: () -> Unit,
) : JPanel(BorderLayout()) {
    private val label = JLabel("Find")
    val queryField = JTextField()
    private val status = JLabel(" ")
    private val prevButton = flatButton("↑") { onFind(false) }
    private val nextButton = flatButton("↓") { onFind(true) }
    private val closeButton = flatButton("✕") { onClose() }
    private var colors = FindBarColors(
        background = java.awt.Color(0x171511),
        foreground = java.awt.Color(0xE4DED0),
        secondary = java.awt.Color(0x8E8779),
        fieldBackground = java.awt.Color(0x11100D),
        fieldBorder = java.awt.Color(0x302D27),
        fieldBorderFocused = java.awt.Color(0xD18A4B),
        buttonHover = java.awt.Color(0x2C2117),
    )
    private var suppressQueryCallback = false

    var query: String
        get() = queryField.text.orEmpty()
        set(value) {
            suppressQueryCallback = true
            queryField.text = value
            suppressQueryCallback = false
        }

    init {
        border = EmptyBorder(6, 10, 6, 8)
        isOpaque = true
        label.border = EmptyBorder(0, 0, 0, 8)
        status.border = EmptyBorder(0, 8, 0, 4)
        queryField.preferredSize = Dimension(220, 26)
        queryField.minimumSize = Dimension(120, 26)
        queryField.columns = 18
        queryField.border = fieldBorder(focused = false)
        queryField.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) {
                queryField.border = fieldBorder(focused = true)
            }

            override fun focusLost(e: FocusEvent?) {
                queryField.border = fieldBorder(focused = false)
            }
        })
        queryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() {
                if (!suppressQueryCallback) onQueryChanged()
            }
        })
        queryField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ESCAPE -> {
                        onClose()
                        e.consume()
                    }
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                        onFind(false)
                        e.consume()
                    }
                    e.keyCode == KeyEvent.VK_ENTER -> {
                        onFind(true)
                        e.consume()
                    }
                }
            }
        })
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(label)
            add(queryField)
            add(prevButton)
            add(nextButton)
            add(status)
        }
        add(controls, BorderLayout.CENTER)
        add(closeButton, BorderLayout.EAST)
        preferredSize = Dimension(0, 40)
    }

    fun focusField() {
        queryField.requestFocusInWindow()
        queryField.selectAll()
    }

    fun setStatus(text: String) {
        status.text = text.ifBlank { " " }
    }

    fun applyTheme(next: FindBarColors) {
        colors = next
        background = next.background
        label.foreground = next.secondary
        status.foreground = next.secondary
        queryField.background = next.fieldBackground
        queryField.foreground = next.foreground
        queryField.caretColor = next.fieldBorderFocused
        queryField.selectionColor = blend(next.fieldBorderFocused, next.fieldBackground, 0.55f)
        queryField.selectedTextColor = next.foreground
        queryField.border = fieldBorder(focused = queryField.hasFocus())
        listOf(prevButton, nextButton, closeButton).forEach { button ->
            button.foreground = next.foreground
            button.background = next.background
        }
        repaint()
    }

    private fun fieldBorder(focused: Boolean) = BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(if (focused) colors.fieldBorderFocused else colors.fieldBorder, 1),
        EmptyBorder(3, 8, 3, 8),
    )

    private fun flatButton(label: String, action: () -> Unit): JButton {
        return object : JButton(label) {
            private var hovered = false

            init {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(28, 26)
                font = font.deriveFont(Font.PLAIN, 12f)
                toolTipText = when (label) {
                    "↑" -> "Previous match"
                    "↓" -> "Next match"
                    else -> "Close"
                }
                ui = object : BasicButtonUI() {}
                addActionListener { action() }
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: java.awt.event.MouseEvent?) {
                        hovered = false
                        repaint()
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (hovered || model.isArmed) {
                    g2.color = colors.buttonHover
                    g2.fillRoundRect(1, 1, width - 2, height - 2, 6, 6)
                }
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }
}
