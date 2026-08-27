package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JScrollPane
import javax.swing.KeyStroke

@Composable
actual fun BugLogcatTextSurface(
    text: String,
    modifier: Modifier,
) {
    SwingPanel(
        modifier = modifier,
        background = Color.Black,
        factory = { BugLogcatPanel() },
        update = { panel -> panel.updateText(text.ifBlank { "<no logcat captured>" }) },
    )
}

private class BugLogcatPanel : RTextScrollPane() {
    private val editorBackground = java.awt.Color(0x000000)
    private val gutterBackground = java.awt.Color(0x0F0D0A)
    private val primaryText = java.awt.Color(0xE4DED0)
    private val secondaryText = java.awt.Color(0x8E8779)
    private val rust = java.awt.Color(0xD18A4B)
    private val editor = RSyntaxTextArea().apply {
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
        isEditable = false
        antiAliasingEnabled = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = editorBackground
        foreground = primaryText
        caretColor = rust
        selectionColor = java.awt.Color(0x514D44)
        selectedTextColor = java.awt.Color(0xF4F1E8)
        currentLineHighlightColor = java.awt.Color(0x11100D)
        lineWrap = false
        wrapStyleWord = false
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "select-all")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.META_DOWN_MASK), "select-all")
    }

    init {
        setViewportView(editor)
        lineNumbersEnabled = false
        background = editorBackground
        viewport.background = editorBackground
        gutter.background = gutterBackground
        gutter.lineNumberColor = secondaryText
        gutter.currentLineNumberColor = rust
        gutter.borderColor = java.awt.Color(0x302D27)
        border = null
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.applyAndyScrollTheme(java.awt.Color(0x11100D))
        horizontalScrollBar.applyAndyScrollTheme(java.awt.Color(0x11100D))
    }

    fun updateText(value: String) {
        if (editor.text == value) return
        val caret = editor.caretPosition.coerceAtMost(value.length)
        editor.text = value
        editor.caretPosition = caret
    }
}
