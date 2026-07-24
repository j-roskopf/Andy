package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Soft cap for cumulative `scrollback.ansi` files (~5 MB). */
internal const val SCROLLBACK_MAX_BYTES: Int = 5 * 1024 * 1024

internal const val SCROLLBACK_SESSION_SEPARATOR: String =
    "\r\n\u001b[90m─── ───\u001b[0m\r\n"

/**
 * Export JediTerm history + screen as newline-oriented ANSI so replay yields
 * readable scrollback (not raw TUI redraw noise).
 */
fun TerminalTextBuffer.exportScrollbackAnsi(): String {
    lock()
    try {
        val out = StringBuilder()
        val history = historyLinesStorage
        for (i in 0 until history.size) {
            appendLineAsAnsi(out, history[i])
            out.append("\r\n")
        }
        val screen = screenLinesStorage
        var lastContent = screen.size - 1
        while (lastContent >= 0 && screen[lastContent].isNulOrEmpty) {
            lastContent--
        }
        for (i in 0..lastContent) {
            appendLineAsAnsi(out, screen[i])
            out.append("\r\n")
        }
        if (out.isNotEmpty()) out.append("\u001b[0m")
        return out.toString()
    } finally {
        unlock()
    }
}

/** Encode a [TextStyle] as an SGR sequence (reset + attributes + colors). */
internal fun TextStyle.toAnsiSgr(): String {
    val codes = mutableListOf("0")
    if (hasOption(TextStyle.Option.BOLD)) codes += "1"
    if (hasOption(TextStyle.Option.DIM)) codes += "2"
    if (hasOption(TextStyle.Option.ITALIC)) codes += "3"
    if (hasOption(TextStyle.Option.UNDERLINED)) codes += "4"
    if (hasOption(TextStyle.Option.SLOW_BLINK) || hasOption(TextStyle.Option.RAPID_BLINK)) codes += "5"
    if (hasOption(TextStyle.Option.INVERSE)) codes += "7"
    if (hasOption(TextStyle.Option.HIDDEN)) codes += "8"
    foreground?.let { codes += it.toAnsiFgCodes() }
    background?.let { codes += it.toAnsiBgCodes() }
    return "\u001b[${codes.joinToString(";")}m"
}

/**
 * Trim [content] to [maxBytes], dropping oldest complete lines when over the cap.
 */
internal fun capScrollbackSize(content: String, maxBytes: Int = SCROLLBACK_MAX_BYTES): String {
    val bytes = content.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= maxBytes) return content
    var cut = bytes.size - maxBytes
    while (cut < bytes.size && bytes[cut] != '\n'.code.toByte() && bytes[cut] != '\r'.code.toByte()) {
        cut++
    }
    while (cut < bytes.size && (bytes[cut] == '\n'.code.toByte() || bytes[cut] == '\r'.code.toByte())) {
        cut++
    }
    if (cut >= bytes.size) return content.takeLast(maxBytes.coerceAtMost(content.length))
    return String(bytes, cut, bytes.size - cut, StandardCharsets.UTF_8)
}

internal fun atomicWriteText(file: File, content: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(file)) {
        file.writeText(content)
        tmp.delete()
    }
}

/**
 * Build a read-only JediTerm widget that replays [ansi] instantly and stays
 * open for scrolling. User keystrokes are discarded.
 */
fun createScrollbackReplayWidget(
    ansi: String,
    cols: Int = 120,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
): JediTermWidget {
    // CSI ?25l hides the cursor so history doesn't look like a live prompt.
    val connector = AnsiReplayTtyConnector(ansi + "\u001b[?25l")
    return onSwingEdt {
        createAndyJediTermWidget(cols, rows, appearance).apply {
            ttyConnector = connector
            start()
            // Hide the caret — this is history, not an input surface.
            runCatching { terminal.setCursorVisible(false) }
            runCatching { terminalPanel.setCursorVisible(false) }
            // Give the emulator a beat to consume the buffer, then jump to end.
            SwingUtilitiesInvokeLater {
                runCatching { terminal.setCursorVisible(false) }
                runCatching { terminalPanel.setCursorVisible(false) }
                runCatching { terminalPanel.scrollToShowAllOutput() }
            }
        }
    }
}

private fun SwingUtilitiesInvokeLater(block: () -> Unit) {
    javax.swing.SwingUtilities.invokeLater(block)
}

private fun appendLineAsAnsi(out: StringBuilder, line: TerminalLine) {
    if (line.isNulOrEmpty) return
    line.forEachEntry { entry ->
        if (entry.isNul) return@forEachEntry
        val text = entry.text.toString()
        if (text.isEmpty()) return@forEachEntry
        out.append(entry.style.toAnsiSgr())
        out.append(text.trimEnd('\u0000'))
    }
}

private fun TerminalColor.toAnsiFgCodes(): List<String> =
    if (isIndexed) {
        listOf("38", "5", colorIndex.toString())
    } else {
        val c = toColor()
        listOf("38", "2", c.red.toString(), c.green.toString(), c.blue.toString())
    }

private fun TerminalColor.toAnsiBgCodes(): List<String> =
    if (isIndexed) {
        listOf("48", "5", colorIndex.toString())
    } else {
        val c = toColor()
        listOf("48", "2", c.red.toString(), c.green.toString(), c.blue.toString())
    }

/**
 * Feeds [ansi] to JediTerm once, then parks the reader so the session stays
 * connected for scrollback viewing. Writes are ignored (read-only).
 */
internal class AnsiReplayTtyConnector(
    ansi: String,
) : TtyConnector {
    private val chars = ansi.toCharArray()
    private var pos = 0
    private val closed = AtomicBoolean(false)

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        if (closed.get()) return -1
        if (pos < chars.size) {
            val n = minOf(length, chars.size - pos)
            System.arraycopy(chars, pos, buf, offset, n)
            pos += n
            return n
        }
        // Park until close so JediTerm keeps the buffer on screen.
        while (!closed.get() && pos >= chars.size) {
            runCatching { Thread.sleep(200) }
        }
        return if (closed.get()) -1 else 0
    }

    override fun write(bytes: ByteArray) {
        // Read-only replay — discard input.
    }

    override fun write(string: String) {
        // Read-only replay — discard input.
    }

    override fun isConnected(): Boolean = !closed.get()

    override fun waitFor(): Int {
        while (!closed.get()) {
            runCatching { Thread.sleep(200) }
        }
        return 0
    }

    override fun ready(): Boolean = !closed.get() && pos < chars.size

    override fun getName(): String = "AndyScrollback"

    override fun close() {
        closed.set(true)
    }
}
