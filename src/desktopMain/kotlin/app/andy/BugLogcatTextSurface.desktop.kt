package app.andy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.plaf.basic.BasicScrollBarUI

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
        verticalScrollBar.applyAndyTheme()
        horizontalScrollBar.applyAndyTheme()
    }

    fun updateText(value: String) {
        if (editor.text == value) return
        val caret = editor.caretPosition.coerceAtMost(value.length)
        editor.text = value
        editor.caretPosition = caret
    }
}

private fun JScrollBar.applyAndyTheme() {
    unitIncrement = 20
    blockIncrement = 160
    background = java.awt.Color(0x11100D)
    ui = object : BasicScrollBarUI() {
        private val thumb = java.awt.Color(0x514D44)
        private val thumbHover = java.awt.Color(0xD18A4B)
        private val track = java.awt.Color(0x11100D)

        override fun configureScrollBarColors() {
            thumbColor = thumb
            thumbHighlightColor = thumb
            thumbDarkShadowColor = thumb
            thumbLightShadowColor = thumb
            trackColor = track
            trackHighlightColor = track
        }

        override fun paintThumb(g: java.awt.Graphics, c: javax.swing.JComponent, thumbBounds: Rectangle) {
            if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
            val g2 = g.create() as java.awt.Graphics2D
            g2.color = if (isThumbRollover) thumbHover else thumb
            g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2, thumbBounds.width - 4, thumbBounds.height - 4, 8, 8)
            g2.dispose()
        }

        override fun paintTrack(g: java.awt.Graphics, c: javax.swing.JComponent, trackBounds: Rectangle) {
            g.color = track
            g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height)
        }

        override fun createDecreaseButton(orientation: Int): JButton = invisibleButton()
        override fun createIncreaseButton(orientation: Int): JButton = invisibleButton()
        private fun invisibleButton() = JButton().apply {
            preferredSize = java.awt.Dimension(0, 0)
            minimumSize = java.awt.Dimension(0, 0)
            maximumSize = java.awt.Dimension(0, 0)
        }
    }
}
