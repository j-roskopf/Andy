package app.andy.terminal

import app.andy.model.TerminalAppearanceSnapshot
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.session.TerminalSession as KetraSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingTerminal
import java.awt.event.MouseWheelListener
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.WeakHashMap
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

    /**
     * Latest OSC title/progress, and how far the buffer has been scanned for them.
     *
     * Callers poll these a few times a second, and answering by rescanning meant copying
     * and regex-scanning up to [SCROLLBACK_MAX_BYTES] per poll per session — the dominant
     * idle cost. Instead each byte is scanned once, on read, so [append] stays as cheap as
     * it was: it sits on the PTY read path ahead of the emulator, and work added there
     * delays output reaching the screen.
     */
    private var latestTitle: String = ""

    private var latestProgress: String = ""

    private var scanPos: Int = 0

    /**
     * Incomplete trailing UTF-8 bytes from the previous [append]. Decoding each PTY read in
     * isolation turns a glyph split across chunks into U+FFFD in scrollback replay.
     */
    private var pendingUtf8 = ByteArray(0)

    /**
     * Bytes ever appended. The tee sits on the PTY read path ahead of the emulator, so this
     * only moves when the host actually sent something — which makes it a sound "the screen
     * may have changed" signal for pollers. Unchanged is proof of *no* change; a change is
     * merely possible (a chunk can be pure OSC), so acting on it costs at most one wasted
     * poll while never missing an update.
     */
    @Volatile
    private var bytesSeen: Long = 0L

    /** Monotonic counter of bytes read from the host. See [bytesSeen]. */
    fun outputGeneration(): Long = bytesSeen

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            val decoded = decodeUtf8CarryingIncomplete(bytes, offset, length)
            if (decoded.isNotEmpty()) {
                buffer.append(decoded)
                trimToCap()
            }
            bytesSeen += length
        }
    }

    fun snapshot(): String = synchronized(lock) { buffer.toString() }

    /** Latest OSC 0/2 title seen on the stream, or empty. */
    fun latestOscTitle(): String = synchronized(lock) {
        scanPending()
        latestTitle
    }

    /** Latest ConEmu/OSC 9 progress payload (`4;…`), or empty. */
    fun latestOscProgress(): String = synchronized(lock) {
        scanPending()
        latestProgress
    }

    fun clear() {
        synchronized(lock) {
            buffer.setLength(0)
            scanPos = 0
            latestTitle = ""
            latestProgress = ""
            pendingUtf8 = ByteArray(0)
        }
    }

    /**
     * Decode [bytes] as UTF-8, prefixing any [pendingUtf8] leftover. Complete characters go
     * into the returned string; a trailing partial sequence stays in [pendingUtf8].
     */
    private fun decodeUtf8CarryingIncomplete(bytes: ByteArray, offset: Int, length: Int): String {
        val combined: ByteArray
        val start: Int
        val total: Int
        if (pendingUtf8.isEmpty()) {
            combined = bytes
            start = offset
            total = length
        } else {
            combined = ByteArray(pendingUtf8.size + length)
            pendingUtf8.copyInto(combined)
            bytes.copyInto(combined, destinationOffset = pendingUtf8.size, startIndex = offset, endIndex = offset + length)
            start = 0
            total = combined.size
        }
        val completeLen = utf8CompletePrefixLength(combined, start, total)
        pendingUtf8 = if (completeLen < total) {
            combined.copyOfRange(start + completeLen, start + total)
        } else {
            ByteArray(0)
        }
        if (completeLen == 0) return ""
        return String(combined, start, completeLen, StandardCharsets.UTF_8)
    }

    /**
     * Drop oldest complete lines once the buffer runs [TRIM_SLACK] past the cap. Trimming on
     * every append instead re-copied the whole buffer per PTY read once the cap was reached.
     */
    private fun trimToCap() {
        if (buffer.length <= maxBytes + TRIM_SLACK) return
        var cut = buffer.length - maxBytes
        while (cut < buffer.length && buffer[cut] != '\n' && buffer[cut] != '\r') cut++
        if (cut < buffer.length) cut++
        buffer.delete(0, cut)
        scanPos = (scanPos - cut).coerceAtLeast(0)
    }

    /**
     * Scan only what has arrived since the last read, re-covering [OSC_CARRY_MAX] chars so a
     * sequence split across reads is still matched. Re-matching a sequence is harmless — the
     * fields hold "latest wins", and the overlap is a contiguous suffix, so order is kept.
     */
    private fun scanPending() {
        if (scanPos >= buffer.length) return
        val start = (scanPos - OSC_CARRY_MAX).coerceAtLeast(0)
        for (match in OSC_PATTERN.findAll(buffer.substring(start))) {
            val payload = match.groupValues[2]
            when (match.groupValues[1]) {
                "0", "2" -> latestTitle = payload
                "9" -> latestProgress = if (payload.startsWith("4;")) payload else ""
            }
        }
        scanPos = buffer.length
    }

    private companion object {
        /** Appends tolerated past the cap before paying for a trim. */
        private const val TRIM_SLACK = 64 * 1024

        /** Longest partial OSC sequence carried between chunks. Titles are far shorter. */
        private const val OSC_CARRY_MAX = 4096
    }
}

/**
 * Length of the leading complete UTF-8 sequences in [bytes] `[offset, offset + length)`.
 * Stops before a trailing partial multi-byte character so the caller can carry it.
 */
internal fun utf8CompletePrefixLength(bytes: ByteArray, offset: Int, length: Int): Int {
    val end = offset + length
    var i = offset
    while (i < end) {
        val lead = bytes[i].toInt() and 0xFF
        var need = when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1 // lone continuation / invalid lead — consume so it becomes U+FFFD once
        }
        if (need > 1) {
            for (j in 1 until need) {
                if (i + j >= end) break
                if (bytes[i + j].toInt() and 0xC0 != 0x80) {
                    need = 1
                    break
                }
            }
        }
        if (i + need > end) return i - offset
        i += need
    }
    return length
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
 * Widest visible row in [content], the column count a replay needs to reproduce the
 * original layout instead of hard-wrapping every boxed TUI line.
 */
internal fun scrollbackReplayColumns(
    content: String,
    minColumns: Int = 100,
    maxColumns: Int = 400,
): Int {
    val widest = content.lineSequence().maxOfOrNull { stripAnsi(it).trimEnd().length } ?: 0
    // One spare column: a row exactly as wide as the terminal triggers an auto-wrap
    // that turns the following newline into a blank line.
    return (widest + 1).coerceIn(minColumns, maxColumns)
}

/**
 * Build a read-only [SwingTerminal] that replays [content] instantly and stays
 * open for scrolling. User keystrokes are discarded by the parked connector.
 *
 * [content] is written to the emulator verbatim so colors, indentation and box
 * drawing land exactly as they did live. Legacy raw PTY tees must be collapsed to
 * text by the caller first — replaying their cursor motion paints overlapping garbage.
 *
 * Dispose with [disposeScrollbackReplayTerminal] so the replay session goes with the widget.
 */
fun createScrollbackReplayTerminal(
    content: String,
    cols: Int = 0,
    rows: Int = 32,
    appearance: TerminalAppearanceSnapshot = TerminalAppearanceSnapshot(),
): SwingTerminal {
    val display = content.trimEnd().ifBlank { "(no readable history for this chat)" }
    val columns = if (cols > 0) cols else scrollbackReplayColumns(display)
    val payload = (display.replace("\r\n", "\n").replace("\n", "\r\n") + "\u001b[0m\u001b[?25l")
        .toByteArray(StandardCharsets.UTF_8)
    val buffer = TerminalBuffers.create(
        width = columns,
        height = rows,
        maxHistory = KetraTermBackend.DEFAULT_MAX_HISTORY,
    )
    val session = KetraSession.create(terminal = buffer, connector = ParkedTerminalConnector())
    session.start(columns, rows)
    session.onBytes(payload, 0, payload.size)
    return onSwingEdt {
        val settings = appearance.toScrollbackReplaySettings(columns = columns, rows = rows)
        SwingTerminal(
            settingsProvider = { settings },
            hostServices = andyScrollbackSwingHostServices(),
        ).also { terminal ->
            terminal.bind(session)
            // History is view-only — keep focus out of the widget so typing goes to
            // the follow-up composer (or elsewhere), not the parked replay session.
            terminal.isFocusable = false
            installScrollbackReplayWheelHandler(terminal)
            scrollbackReplaySessions[terminal] = session
        }
    }
}

/**
 * Dispose a widget built by [createScrollbackReplayTerminal], closing its replay session.
 *
 * [SwingTerminal.dispose] only unbinds, so disposing alone leaks the session's render worker
 * thread — one per history peek — for the life of the app.
 */
fun disposeScrollbackReplayTerminal(terminal: SwingTerminal) {
    val session = onSwingEdt {
        runCatching { terminal.dispose() }
        scrollbackReplaySessions.remove(terminal)
    } ?: return
    // TerminalSession.close awaits its render worker, so keep it off the EDT.
    Thread({ runCatching { session.close() } }, "andy-scrollback-replay-close").apply {
        isDaemon = true
        start()
    }
}

/** Replay session per viewer widget, so disposal can close it. EDT only. */
private val scrollbackReplaySessions = WeakHashMap<SwingTerminal, KetraSession>()

/**
 * KetraTerm only scrolls on wheel when the terminal is focused. Replay viewers stay
 * unfocusable so follow-up typing stays in the composer, so wheel deltas are applied
 * explicitly here.
 */
internal fun installScrollbackReplayWheelHandler(terminal: SwingTerminal) {
    terminal.addMouseWheelListener(
        MouseWheelListener { event ->
            val delta = terminalWheelScrollDelta(event)
            if (delta != 0.0) {
                terminal.scrollViewportBy(delta)
            }
        },
    )
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

/** [TeeTerminalConnector] that strips agent-CLI redraw noise before the emulator sees it. */
internal class AgentCliTeeTerminalConnector(
    private val delegate: TerminalConnector,
    private val tee: ScrollbackAnsiTee,
) : TerminalConnector {
    override fun start(listener: TerminalConnectorListener) {
        delegate.start(
            object : TerminalConnectorListener {
                override fun onBytes(bytes: ByteArray, offset: Int, length: Int) {
                    val (sanitized, off, len) = sanitizeAgentCliPtyChunk(bytes, offset, length)
                    tee.append(sanitized, off, len)
                    listener.onBytes(sanitized, off, len)
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

internal fun <T> onSwingEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
