package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.session.TerminalSession as KetraSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Soft cap for cumulative `scrollback.ansi` files (~5 MB). */
internal const val SCROLLBACK_MAX_BYTES: Int = 5 * 1024 * 1024

internal const val SCROLLBACK_SESSION_SEPARATOR: String = "\n─── ───\n"

/**
 * Accumulates raw PTY stdout (host→emulator) bytes for durable ANSI scrollback.
 * Soft-caps at [SCROLLBACK_MAX_BYTES] by dropping oldest complete lines.
 */
class ScrollbackAnsiTee(
    private val maxBytes: Int = SCROLLBACK_MAX_BYTES,
) {
    private val lock = Any()
    private val buffer = StringBuilder()

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val chunk = String(bytes, offset, length, StandardCharsets.UTF_8)
        synchronized(lock) {
            buffer.append(chunk)
            if (buffer.length > maxBytes) {
                val capped = capScrollbackSize(buffer.toString(), maxBytes)
                buffer.setLength(0)
                buffer.append(capped)
            }
        }
    }

    fun snapshot(): String = synchronized(lock) { buffer.toString() }

    fun clear() {
        synchronized(lock) { buffer.setLength(0) }
    }
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
 * True when [content] looks like a raw PTY tee (spinner redraws, cursor motion, etc.)
 * rather than resolved scrollback text from [io.github.ketraterm.core.api.TerminalInspector.getAllAsString].
 */
internal fun looksLikeRawAnsiTee(content: String): Boolean {
    if (!content.contains('\u001b')) return false
    var escapes = 0
    for (ch in content) {
        if (ch == '\u001b' && ++escapes > 8) return true
    }
    return false
}

/**
 * Collapse legacy raw ANSI tee streams into readable plain text. New scrollback files
 * are already resolved on write; this mainly repairs pre-fix `scrollback.ansi` blobs.
 */
internal fun resolveScrollbackForReplay(
    content: String,
    cols: Int = 120,
    rows: Int = 32,
): String {
    if (content.isBlank() || !looksLikeRawAnsiTee(content)) return content
    return joinReadableLines(replayCaptureReadableLines(content, cols, rows))
}

/** Feed legacy ANSI in chunks and capture readable lines before spinner history pushes them out. */
internal fun replayCaptureReadableLines(
    content: String,
    cols: Int = 120,
    rows: Int = 32,
    chunkSize: Int = 8_192,
): List<String> {
    val bytes = content.toByteArray(StandardCharsets.UTF_8)
    val buffer = TerminalBuffers.create(width = cols, height = rows, maxHistory = KetraTermBackend.DEFAULT_MAX_HISTORY)
    val session = KetraSession.create(terminal = buffer, connector = ParkedTerminalConnector())
    val seen = LinkedHashSet<String>()
    val captured = mutableListOf<String>()
    return try {
        session.start(cols, rows)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(chunkSize, bytes.size - offset)
            session.onBytes(bytes, offset, length)
            offset += length
            for (line in captureNewReadableLines(buffer, seen)) {
                captured += line
            }
        }
        captured
    } finally {
        runCatching { session.close() }
    }
}

/**
 * Build a read-only [SwingTerminal] that replays [content] instantly and stays
 * open for scrolling. User keystrokes are discarded by the parked connector.
 *
 * Content is cleaned with [formatScrollbackForDisplay] first so TUI chrome
 * (slash menus, box rules, spinners) does not paint as overlapping garbage
 * when the viewer width differs from the original capture.
 */
fun createScrollbackReplayTerminal(
    content: String,
    cols: Int = 120,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
): SwingTerminal {
    val resolved = resolveScrollbackForReplay(content, cols, rows)
    val display = formatScrollbackForDisplay(resolved).ifBlank {
        resolved.lines()
            .filterNot { isScrollbackDisplayNoise(it) }
            .joinToString("\n")
            .trim()
    }.ifBlank { "(no readable history for this chat)" }
    // Plain text + newlines — avoid feeding wide TUI rows that wrap/overlap.
    val payload = (display + "\n\u001b[?25l").toByteArray(StandardCharsets.UTF_8)
    val connector = AnsiReplayConnector(payload)
    val buffer = TerminalBuffers.create(width = cols, height = rows, maxHistory = KetraTermBackend.DEFAULT_MAX_HISTORY)
    val session = KetraSession.create(terminal = buffer, connector = connector)
    session.start(cols, rows)
    return onSwingEdt {
        val settings = appearance.toSwingSettings(columns = cols, rows = rows)
        SwingTerminal(settingsProvider = { settings }).also { terminal ->
            terminal.bind(session)
            SwingUtilities.invokeLater {
                // Give the emulator a beat to consume the buffer, then jump to end.
                runCatching { terminal.repaint() }
            }
        }
    }
}

/** Forwards transport events while teeing host→emulator stdout into [tee]. */
internal class TeeTerminalConnector(
    private val delegate: TerminalConnector,
    private val tee: ScrollbackAnsiTee,
) : TerminalConnector {
    override fun start(listener: TerminalConnectorListener) {
        delegate.start(
            object : TerminalConnectorListener {
                override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
                    tee.append(bytes, offset, length)
                    listener.onBytes(bytes, offset, length)
                }

                override fun onClosed(exitCode: Int?) = listener.onClosed(exitCode)

                override fun onError(error: Throwable) = listener.onError(error)
            },
        )
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) =
        delegate.write(bytes, offset, length)

    override fun resize(columns: Int, rows: Int) = delegate.resize(columns, rows)

    override fun close() = delegate.close()
}

/** Parked connector for sessions fed manually via [io.github.ketraterm.session.TerminalSession.onBytes]. */
internal class ParkedTerminalConnector : TerminalConnector {
    override fun start(listener: TerminalConnectorListener) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    override fun resize(columns: Int, rows: Int) = Unit
    override fun close() = Unit
}

/** Feeds [ansi] synchronously when the session connector starts. */
internal class SynchronousAnsiConnector(
    private val ansi: ByteArray,
) : TerminalConnector {
    override fun start(listener: TerminalConnectorListener) {
        if (ansi.isNotEmpty()) {
            listener.onBytes(ansi, 0, ansi.size)
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

    override fun resize(columns: Int, rows: Int) = Unit

    override fun close() = Unit
}

/**
 * Feeds [ansi] bytes once, then parks until [close] so the replay session stays
 * connected for scrollback viewing. Writes are ignored (read-only).
 */
internal class AnsiReplayConnector(
    private val ansi: ByteArray,
) : TerminalConnector {
    private val closed = AtomicBoolean(false)
    private var listener: TerminalConnectorListener? = null
    private var reader: Thread? = null

    override fun start(listener: TerminalConnectorListener) {
        check(this.listener == null) { "connector already started" }
        this.listener = listener
        reader = Thread({
            if (ansi.isNotEmpty()) {
                listener.onBytes(ansi, 0, ansi.size)
            }
            while (!closed.get()) {
                runCatching { Thread.sleep(200) }
            }
            listener.onClosed(0)
        }, "andy-scrollback-replay").apply {
            isDaemon = true
            start()
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // Read-only replay — discard input.
    }

    override fun resize(columns: Int, rows: Int) = Unit

    override fun close() {
        closed.set(true)
        reader?.interrupt()
    }
}

internal fun <T> onSwingEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
