package app.andy.terminal

import io.github.ketraterm.protocol.NotificationLevel
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Andy-owned AWT tray notifications for KetraTerm OSC 9 / 777 host events.
 *
 * Mirrors KetraTerm's unpublished DesktopNotificationManager: one reusable tray
 * icon, auto-remove after idle, branded as Andy.
 */
object AndyDesktopNotificationManager {
    private var trayIcon: TrayIcon? = null
    private var removeTimer: Timer? = null
    private const val REMOVE_DELAY_MILLIS = 10_000
    private const val MAX_TITLE = 256
    private const val MAX_BODY = 1024

    fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel = NotificationLevel.INFO,
    ) {
        val displayTitle = title.trim().take(MAX_TITLE).ifBlank { "Andy" }
        val displayBody = body.trim().take(MAX_BODY)
        if (displayBody.isEmpty() && title.isBlank()) return

        SwingUtilities.invokeLater {
            if (!SystemTray.isSupported()) return@invokeLater
            if (GraphicsEnvironment.isHeadless()) return@invokeLater

            val systemTray = runCatching { SystemTray.getSystemTray() }.getOrNull() ?: return@invokeLater

            var icon = trayIcon
            if (icon == null) {
                icon = TrayIcon(createTrayImage(), "Andy").also {
                    it.isImageAutoSize = true
                    trayIcon = it
                }
            }

            if (!systemTray.trayIcons.contains(icon)) {
                runCatching { systemTray.add(icon) }.onFailure { return@invokeLater }
            }

            val messageType = when (level) {
                NotificationLevel.INFO -> TrayIcon.MessageType.INFO
                NotificationLevel.WARNING -> TrayIcon.MessageType.WARNING
                NotificationLevel.ERROR -> TrayIcon.MessageType.ERROR
                NotificationLevel.NONE -> TrayIcon.MessageType.NONE
            }
            icon.displayMessage(displayTitle, displayBody, messageType)

            removeTimer?.stop()
            val timer = Timer(REMOVE_DELAY_MILLIS) {
                runCatching { systemTray.remove(icon) }
            }
            timer.isRepeats = false
            timer.start()
            removeTimer = timer
        }
    }

    private fun createTrayImage(): Image {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(30, 144, 255)
            g.fillRoundRect(0, 0, 16, 16, 4, 4)
            g.color = Color.WHITE
            g.font = Font(Font.MONOSPACED, Font.BOLD, 12)
            g.drawString("A", 3, 12)
        } finally {
            g.dispose()
        }
        return image
    }
}
