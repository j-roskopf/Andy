package app.andy

import androidx.compose.ui.graphics.Color
import app.andy.model.EditorSyntaxTheme
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.Style
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font

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
        pane.verticalScrollBar.applyAndyScrollTheme(AndyEditorBackground)
        pane.horizontalScrollBar.applyAndyScrollTheme(AndyEditorBackground)
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
        pane.verticalScrollBar.applyAndyScrollTheme(editor.background)
        pane.horizontalScrollBar.applyAndyScrollTheme(editor.background)
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
