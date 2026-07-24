package app.andy

import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.plaf.basic.BasicScrollBarUI

private const val AndyScrollBarBreadth = 14
private const val AndyScrollBarThumbBreadth = 6

/** Andy-styled Swing scrollbar (editor, logcat, terminal). */
internal fun JScrollBar.applyAndyScrollTheme(track: java.awt.Color) {
    unitIncrement = 16
    blockIncrement = 96
    val breadth = if (orientation == JScrollBar.VERTICAL) {
        Dimension(AndyScrollBarBreadth, 0)
    } else {
        Dimension(0, AndyScrollBarBreadth)
    }
    preferredSize = breadth
    minimumSize = breadth
    maximumSize = if (orientation == JScrollBar.VERTICAL) {
        Dimension(AndyScrollBarBreadth, Int.MAX_VALUE)
    } else {
        Dimension(Int.MAX_VALUE, AndyScrollBarBreadth)
    }
    background = track
    ui = AndyScrollBarUi(track)
}

private class AndyScrollBarUi(
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
        val g2 = g.create() as Graphics2D
        g2.color = if (isThumbRollover) thumbHover else thumb
        val thumbBreadth = AndyScrollBarThumbBreadth.coerceAtMost(
            if (scrollbar.orientation == JScrollBar.VERTICAL) thumbBounds.width else thumbBounds.height,
        )
        val corner = thumbBreadth / 2
        if (scrollbar.orientation == JScrollBar.VERTICAL) {
            val left = thumbBounds.x + (thumbBounds.width - thumbBreadth) / 2
            g2.fillRoundRect(
                left,
                thumbBounds.y + 2,
                thumbBreadth,
                thumbBounds.height - 4,
                corner,
                corner,
            )
        } else {
            val top = thumbBounds.y + (thumbBounds.height - thumbBreadth) / 2
            g2.fillRoundRect(
                thumbBounds.x + 2,
                top,
                thumbBounds.width - 4,
                thumbBreadth,
                corner,
                corner,
            )
        }
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
