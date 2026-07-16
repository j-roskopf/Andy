package app.andy

import androidx.compose.ui.graphics.Color
import app.andy.model.EditorSyntaxTheme
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Style
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.plaf.basic.BasicScrollBarUI

internal fun editorPanelBackground(syntaxThemeId: String): Color = when (EditorSyntaxTheme.fromId(syntaxThemeId)) {
    EditorSyntaxTheme.Andy, EditorSyntaxTheme.Dark, EditorSyntaxTheme.Monokai, EditorSyntaxTheme.Druid -> Color(0xFF11100D)
    EditorSyntaxTheme.Idea -> Color(0xFF2B2B2B)
    EditorSyntaxTheme.Eclipse, EditorSyntaxTheme.Vs, EditorSyntaxTheme.Default, EditorSyntaxTheme.DefaultAlt -> Color(0xFFFFFFFF)
}

internal fun applyEditorSyntaxTheme(
    editor: RSyntaxTextArea,
    scrollPane: RTextScrollPane?,
    syntaxThemeId: String,
) {
    when (val theme = EditorSyntaxTheme.fromId(syntaxThemeId)) {
        EditorSyntaxTheme.Andy -> applyAndyEditorTheme(editor, scrollPane)
        else -> applyBundledEditorTheme(editor, scrollPane, theme)
    }
}

private val AndyEditorBackground = java.awt.Color(0x11100D)
private val AndyGutterBackground = java.awt.Color(0x171511)
private val AndyGutterBorder = java.awt.Color(0x302D27)
private val AndyPrimaryText = java.awt.Color(0xE4DED0)
private val AndySecondaryText = java.awt.Color(0x8E8779)
private val AndyRust = java.awt.Color(0xD18A4B)
private val AndyGreen = java.awt.Color(0x94C17A)
private val AndyCyan = java.awt.Color(0x88AFC8)
private val AndyYellow = java.awt.Color(0xE3B05E)
private val AndyRed = java.awt.Color(0xE26F5C)

private fun applyAndyEditorTheme(editor: RSyntaxTextArea, scrollPane: RTextScrollPane?) {
    editor.background = AndyEditorBackground
    editor.currentLineHighlightColor = java.awt.Color(0x1D1A16)
    editor.foreground = AndyPrimaryText
    editor.caretColor = AndyRust
    editor.selectionColor = java.awt.Color(0x514D44)
    editor.selectedTextColor = java.awt.Color(0xF4F1E8)
    editor.markOccurrencesColor = java.awt.Color(0x2F2A22)
    editor.markAllHighlightColor = java.awt.Color(0x3A3022)
    editor.matchedBracketBGColor = java.awt.Color(0x2C2117)
    editor.matchedBracketBorderColor = AndyRust
    editor.tabLineColor = java.awt.Color(0x302D27)
    editor.hyperlinkForeground = AndyCyan
    editor.syntaxScheme = andySyntaxScheme(editor)
    scrollPane?.let { pane ->
        pane.background = AndyEditorBackground
        pane.viewport.background = AndyEditorBackground
        pane.gutter.background = AndyGutterBackground
        pane.gutter.lineNumberColor = AndySecondaryText
        pane.gutter.currentLineNumberColor = AndyRust
        pane.gutter.borderColor = AndyGutterBorder
        pane.gutter.foldIndicatorForeground = AndySecondaryText
        pane.gutter.foldIndicatorArmedForeground = AndyRust
        pane.gutter.foldBackground = AndyGutterBackground
        pane.verticalScrollBar.applyEditorScrollTheme(AndyEditorBackground)
        pane.horizontalScrollBar.applyEditorScrollTheme(AndyEditorBackground)
    }
}

private fun applyBundledEditorTheme(
    editor: RSyntaxTextArea,
    scrollPane: RTextScrollPane?,
    theme: EditorSyntaxTheme,
) {
    val resource = "/org/fife/ui/rsyntaxtextarea/themes/${theme.id}.xml"
    val stream = Theme::class.java.getResourceAsStream(resource)
        ?: error("Missing RSyntaxTextArea theme resource: $resource")
    stream.use { Theme.load(it).apply(editor) }
    scrollPane?.let { pane ->
        pane.background = editor.background
        pane.viewport.background = editor.background
        pane.gutter.background = editor.background
        pane.gutter.foldBackground = editor.background
        pane.verticalScrollBar.applyEditorScrollTheme(editor.background)
        pane.horizontalScrollBar.applyEditorScrollTheme(editor.background)
    }
}

private fun andySyntaxScheme(editor: RSyntaxTextArea): org.fife.ui.rsyntaxtextarea.SyntaxScheme {
    val scheme = editor.syntaxScheme.clone() as org.fife.ui.rsyntaxtextarea.SyntaxScheme
    val font = editor.font
    fun style(token: Int, color: java.awt.Color, bold: Boolean = false) {
        scheme.setStyle(token, Style(color, null, if (bold) font.deriveFont(Font.BOLD) else font))
    }
    style(TokenTypes.IDENTIFIER, AndyPrimaryText)
    style(TokenTypes.RESERVED_WORD, AndyRust, bold = true)
    style(TokenTypes.RESERVED_WORD_2, AndyRust)
    style(TokenTypes.FUNCTION, AndyCyan)
    style(TokenTypes.LITERAL_BOOLEAN, java.awt.Color(0xB865FF), bold = true)
    style(TokenTypes.LITERAL_NUMBER_DECIMAL_INT, AndyYellow)
    style(TokenTypes.LITERAL_NUMBER_FLOAT, AndyYellow)
    style(TokenTypes.LITERAL_NUMBER_HEXADECIMAL, AndyYellow)
    style(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, AndyGreen)
    style(TokenTypes.LITERAL_CHAR, AndyGreen)
    style(TokenTypes.DATA_TYPE, AndyCyan)
    style(TokenTypes.VARIABLE, AndyPrimaryText)
    style(TokenTypes.OPERATOR, AndyRed)
    style(TokenTypes.SEPARATOR, AndyRed)
    style(TokenTypes.COMMENT_EOL, AndySecondaryText)
    style(TokenTypes.COMMENT_MULTILINE, AndySecondaryText)
    style(TokenTypes.COMMENT_DOCUMENTATION, AndySecondaryText)
    style(TokenTypes.MARKUP_TAG_NAME, AndyRust)
    style(TokenTypes.MARKUP_TAG_ATTRIBUTE, AndyCyan)
    style(TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE, AndyGreen)
    return scheme
}

internal fun JScrollBar.applyEditorScrollTheme(track: java.awt.Color) {
    unitIncrement = 16
    blockIncrement = 96
    preferredSize = if (orientation == JScrollBar.VERTICAL) Dimension(12, 0) else Dimension(0, 12)
    background = track
    ui = EditorScrollBarUi(track)
}

private class EditorScrollBarUi(
    private val track: java.awt.Color,
) : BasicScrollBarUI() {
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
