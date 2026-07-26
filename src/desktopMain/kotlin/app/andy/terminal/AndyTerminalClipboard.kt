package app.andy.terminal

import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuHandler
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import io.github.ketraterm.ui.swing.api.SwingTerminalHostKeyHandler
import java.awt.event.KeyEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * KetraTerm leaves [SwingTerminalHostKeyHandler] / context-menu handlers as NONE by
 * default, so Cmd/Ctrl+V never reaches [SwingTerminal.pasteClipboardText]. Wire both
 * for Andy's embedded agent/project terminals.
 */
internal fun andySwingHostServices(): SwingHostServices =
    SwingHostServices(
        hostKeyHandler = AndyTerminalHostKeyHandler,
        contextMenuHandler = AndyTerminalContextMenuHandler,
    )

/**
 * Host services for finished-chat scrollback replay: copy/select-all only.
 * Every other key (including paste) is consumed so typing cannot mutate the viewer.
 */
internal fun andyScrollbackSwingHostServices(): SwingHostServices =
    SwingHostServices(
        hostKeyHandler = AndyScrollbackHostKeyHandler,
        contextMenuHandler = AndyScrollbackContextMenuHandler,
    )

private object AndyTerminalHostKeyHandler : SwingTerminalHostKeyHandler {
    override fun handleKeyPressed(event: KeyEvent): Boolean {
        val terminal = event.component as? SwingTerminal
            ?: event.source as? SwingTerminal
            ?: return false
        return when {
            isTerminalPasteShortcut(event) -> {
                // Always consume so Meta/Ctrl+V is not forwarded into the PTY.
                terminal.pasteClipboardText()
                true
            }
            isTerminalCopyShortcut(event) -> {
                val copied = terminal.copySelectionToClipboard()
                // macOS Cmd+C always belongs to the host. Elsewhere, only consume when
                // a selection was copied so bare Ctrl+C can still interrupt.
                if (isMacOsTerminalHost()) true else copied
            }
            isTerminalSelectAllShortcut(event) -> {
                terminal.selectAll()
                true
            }
            else -> false
        }
    }
}

private object AndyScrollbackHostKeyHandler : SwingTerminalHostKeyHandler {
    override fun handleKeyPressed(event: KeyEvent): Boolean {
        val terminal = event.component as? SwingTerminal
            ?: event.source as? SwingTerminal
        when {
            terminal != null && isTerminalCopyShortcut(event) -> {
                terminal.copySelectionToClipboard()
            }
            terminal != null && isTerminalSelectAllShortcut(event) -> {
                terminal.selectAll()
            }
        }
        // Always consume — paste and ordinary typing must not reach the replay connector.
        return true
    }
}

private object AndyTerminalContextMenuHandler : SwingTerminalContextMenuHandler {
    override fun handleContextMenu(request: SwingTerminalContextMenuRequest): Boolean {
        val menu = JPopupMenu()
        menu.add(
            JMenuItem("Copy").apply {
                isEnabled = request.hasSelection()
                addActionListener { request.copySelection() }
            },
        )
        menu.add(
            JMenuItem("Paste").apply {
                addActionListener { request.pasteClipboard() }
            },
        )
        menu.addSeparator()
        menu.add(
            JMenuItem("Select All").apply {
                addActionListener { request.selectAll() }
            },
        )
        menu.show(request.terminal, request.x, request.y)
        return true
    }
}

private object AndyScrollbackContextMenuHandler : SwingTerminalContextMenuHandler {
    override fun handleContextMenu(request: SwingTerminalContextMenuRequest): Boolean {
        val menu = JPopupMenu()
        menu.add(
            JMenuItem("Copy").apply {
                isEnabled = request.hasSelection()
                addActionListener { request.copySelection() }
            },
        )
        menu.add(
            JMenuItem("Select All").apply {
                addActionListener { request.selectAll() }
            },
        )
        menu.show(request.terminal, request.x, request.y)
        return true
    }
}

internal fun isTerminalPasteShortcut(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.VK_V) return false
    return if (isMacOsTerminalHost()) {
        event.isMetaDown && !event.isControlDown && !event.isAltDown && !event.isShiftDown
    } else {
        // Ctrl+V pastes in GUI agent chats; Ctrl+Shift+V is the classic Linux terminal bind.
        event.isControlDown && !event.isAltDown && !event.isMetaDown
    }
}

internal fun isTerminalCopyShortcut(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.VK_C) return false
    return if (isMacOsTerminalHost()) {
        event.isMetaDown && !event.isControlDown && !event.isAltDown && !event.isShiftDown
    } else {
        // Prefer Ctrl+Shift+C so bare Ctrl+C remains SIGINT when nothing is selected.
        event.isControlDown && event.isShiftDown && !event.isAltDown && !event.isMetaDown
    }
}

internal fun isTerminalSelectAllShortcut(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.VK_A) return false
    return if (isMacOsTerminalHost()) {
        event.isMetaDown && !event.isControlDown && !event.isAltDown && !event.isShiftDown
    } else {
        event.isControlDown && event.isShiftDown && !event.isAltDown && !event.isMetaDown
    }
}

private fun isMacOsTerminalHost(): Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
