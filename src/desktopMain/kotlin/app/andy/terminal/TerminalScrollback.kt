package app.andy.terminal

import ai.rever.bossterm.compose.daemon.HeadlessTerminalDisplay
import ai.rever.bossterm.core.util.TermSize
import ai.rever.bossterm.terminal.ArrayTerminalDataStream
import ai.rever.bossterm.terminal.RequestOrigin
import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.emulator.BossEmulator
import ai.rever.bossterm.terminal.model.BossTerminal
import ai.rever.bossterm.terminal.model.StyleState
import ai.rever.bossterm.terminal.model.TerminalLine
import ai.rever.bossterm.terminal.model.TerminalTextBuffer
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

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

    /** Absolute character offsets of the retained raw-PTY window. */
    private var retainedStartOffset: Long = 0L

    private var retainedEndOffset: Long = 0L

    /**
     * Identity of the retained stream.
     *
     * A tmux viewer can be released and reattached while its [RawScrollbackFile] survives.
     * Starting every new tee at epoch zero made the new, shorter stream look like an old
     * snapshot and caused the raw mirror to ignore it. Give every tee (and every clear) a
     * process-unique epoch so an incremental reader always recognises a new stream.
     */
    private var epoch: Long = nextScrollbackEpoch()

    /** Monotonic counter of bytes read from the host. See [bytesSeen]. */
    fun outputGeneration(): Long = bytesSeen

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            val decoded = decodeUtf8CarryingIncomplete(bytes, offset, length)
            if (decoded.isNotEmpty()) {
                buffer.append(decoded)
                retainedEndOffset += decoded.length
                trimToCap()
            }
            bytesSeen += length
        }
    }

    fun snapshot(): String = synchronized(lock) { buffer.toString() }

    /**
     * The retained raw-PTY window with its position in the complete output stream.
     *
     * Consumers can feed only bytes appended since their last snapshot even after this tee
     * trims old content; if they fall behind the retained window, they can safely replay the
     * complete current window instead.
     */
    fun snapshotWithOffsets(cursor: ScrollbackAnsiCursor? = null): ScrollbackAnsiSnapshot = synchronized(lock) {
        val requestedStart = cursor
            ?.takeIf { it.epoch == epoch && it.offset in retainedStartOffset..retainedEndOffset }
            ?.offset
            ?: retainedStartOffset
        val contentStart = requestedStart.coerceAtLeast(retainedStartOffset)
        val contentOffset = (contentStart - retainedStartOffset).toInt()
        ScrollbackAnsiSnapshot(
            // The persistence timer normally asks from its last committed offset, so copy
            // only newly arrived characters instead of cloning the entire retained tee.
            content = buffer.substring(contentOffset),
            startOffset = contentStart,
            endOffset = retainedEndOffset,
            epoch = epoch,
        )
    }

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
            retainedStartOffset = 0L
            retainedEndOffset = 0L
            epoch = nextScrollbackEpoch()
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
        retainedStartOffset += cut
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

        private val NextEpoch = AtomicLong()

        private fun nextScrollbackEpoch(): Long = NextEpoch.incrementAndGet()
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
 * Soft cap for `scrollback.raw`. Raw PTY output carries every repaint the emulator later
 * collapses, so it needs more headroom than the transcript derived from it.
 */
const val RAW_SCROLLBACK_MAX_BYTES: Int = 16 * 1024 * 1024

/**
 * Append-only mirror of a session's raw PTY tee.
 *
 * The transcript Andy displays is *derived* from these bytes by replaying them through a
 * terminal emulator and stitching the repaints back together. That derivation is far too
 * expensive to run on a timer — it measured 74% of the whole process — but writing the bytes
 * is nearly free. Persisting raw and deriving on demand keeps the live path to one sequential
 * append per flush, and pays for the reconstruction once, when a reader actually asks.
 *
 * Durability improves as a side effect: the bytes reach disk continuously, so a hard kill
 * leaves a transcript that can still be derived afterwards.
 */
class RawScrollbackFile(
    private val file: File,
    private val maxBytes: Int = RAW_SCROLLBACK_MAX_BYTES,
) {
    private var lastOffset = 0L
    private var lastEpoch = 0L
    private var lastColumns = 0
    private var lastRows = 0
    private var started = false

    /** True once this run has contributed bytes, so callers can tell empty runs apart. */
    var wroteAnything: Boolean = false
        private set

    /**
     * Scope this file to a single run by discarding any earlier run's bytes.
     *
     * Earlier runs are already committed to `scrollback.ansi` when their session ended, so
     * keeping their raw bytes here too would derive them a second time and duplicate the
     * transcript. One run per file keeps "committed history + current run" unambiguous.
     */
    fun startNewRun() {
        runCatching { if (file.isFile) file.delete() }
        lastOffset = 0L
        lastEpoch = 0L
        lastColumns = 0
        lastRows = 0
        started = false
        wroteAnything = false
    }

    /**
     * Position already mirrored to disk.
     *
     * Passing this back to [ScrollbackAnsiTee.snapshotWithOffsets] turns the steady-state
     * flush into a delta copy. The previous no-argument snapshot cloned up to 5 MB every
     * two seconds per live chat even when only a spinner glyph had changed.
     */
    fun cursor(): ScrollbackAnsiCursor? =
        if (started) ScrollbackAnsiCursor(lastOffset, lastEpoch) else null

    /**
     * Append whatever [snapshot] holds beyond the last write. Returns characters appended.
     *
     * The tee retains only a window of the stream, so [ScrollbackAnsiSnapshot.startOffset]
     * can overtake [lastOffset] under sustained output. That is a real gap in history, not a
     * corruption: resume from the oldest retained byte rather than replaying content twice.
     */
    fun append(snapshot: ScrollbackAnsiSnapshot): Int {
        if (!started || snapshot.epoch != lastEpoch) {
            // A cleared tee restarts its offsets; anything it still holds is new to us.
            lastEpoch = snapshot.epoch
            lastOffset = snapshot.startOffset
            lastColumns = 0
            lastRows = 0
            started = true
        }
        if (lastOffset < snapshot.startOffset) lastOffset = snapshot.startOffset
        if (lastOffset >= snapshot.endOffset) return 0
        val delta = snapshot.content.substring((lastOffset - snapshot.startOffset).toInt())
        if (delta.isEmpty()) return 0
        val layoutChanged = snapshot.columns > 0 &&
            snapshot.rows > 0 &&
            (snapshot.columns != lastColumns || snapshot.rows != lastRows)
        val persisted = if (layoutChanged) {
            scrollbackLayoutMarker(snapshot.columns, snapshot.rows) + delta
        } else {
            delta
        }
        appendText(persisted)
        if (snapshot.columns > 0 && snapshot.rows > 0) {
            lastColumns = snapshot.columns
            lastRows = snapshot.rows
        }
        lastOffset = snapshot.endOffset
        return delta.length
    }

    private fun appendText(text: String) {
        file.parentFile?.mkdirs()
        file.appendBytes(text.toByteArray(StandardCharsets.UTF_8))
        wroteAnything = true
        capIfNeeded()
    }

    /**
     * Drop oldest complete lines once past the cap. Rare enough (16 MB of raw PTY) that a
     * rewrite is acceptable; the steady state stays append-only.
     */
    private fun capIfNeeded() {
        if (file.length() <= maxBytes + RAW_TRIM_SLACK) return
        val trimmed = runCatching { capScrollbackSize(file.readText(), maxBytes) }.getOrNull() ?: return
        // A cap can discard the marker that established the active grid. Re-state it at
        // the new beginning so the retained suffix remains independently replayable.
        val retained = if (lastColumns > 0 && lastRows > 0) {
            scrollbackLayoutMarker(lastColumns, lastRows) + trimmed
        } else {
            trimmed
        }
        atomicWriteText(file, retained)
    }

    private companion object {
        /** Rewrite in batches instead of on every append once the cap is reached. */
        const val RAW_TRIM_SLACK = 1 shl 20
    }
}

/**
 * In-band layout record for `scrollback.raw`.
 *
 * PTY output contains absolute cursor addressing but not the terminal dimensions that give
 * those coordinates meaning. This private OSC is written only to Andy's raw mirror (never
 * to the live terminal), then consumed before emulator replay. OSC keeps the file backwards
 * compatible with older builds and terminal tools, which safely ignore unknown commands.
 */
private const val SCROLLBACK_LAYOUT_OSC = "777;andy-grid="
private val ScrollbackLayoutMarker = Regex(
    "\u001B]${Regex.escape(SCROLLBACK_LAYOUT_OSC)}(\\d+)x(\\d+)\u0007",
)

/**
 * Remove attached-client copy-mode redraws from legacy raw tmux recordings.
 *
 * Old Andy builds teed bytes rendered by `tmux attach-session`. Scrolling therefore
 * recorded tmux's yellow `HH:mm [position/history]` client overlay and every historical
 * viewport the user visited as if it were fresh agent output. New builds never persist
 * attached-client bytes. For an existing file, output before the first unmistakable tmux
 * overlay is the pane's real stream and is safe to reconstruct once.
 */
internal fun trimLegacyTmuxCopyModeOutput(content: String): String {
    val marker = LegacyTmuxCopyModeMarker.find(content) ?: return content
    return content.substring(0, marker.range.first).trimEnd()
}

private val LegacyTmuxCopyModeMarker = Regex(
    "\u001B\\[30m\u001B\\[43m\\d{1,2}:\\d{2} \\[\\d+/\\d+]",
)

internal fun scrollbackLayoutMarker(columns: Int, rows: Int): String {
    require(columns > 0)
    require(rows > 0)
    return "\u001B]$SCROLLBACK_LAYOUT_OSC${columns}x${rows}\u0007"
}

private fun layoutMarkerGrid(match: MatchResult): ScrollbackGridSize? {
    val columns = match.groupValues[1].toIntOrNull() ?: return null
    val rows = match.groupValues[2].toIntOrNull() ?: return null
    if (columns <= 0 || rows <= 0) return null
    return ScrollbackGridSize(columns, rows)
}

/**
 * Recover a plausible grid for pre-layout-marker raw captures.
 *
 * Agent TUIs regularly address the bottom row and rightmost columns. CUP/HVP (`H`/`f`)
 * supplies the row count, while CHA (`G`) plus the following visible payload supplies the
 * width (for example `CSI 163 G` followed by two characters means at least 164 columns).
 * If a capture has no such evidence, retain the historical replay defaults.
 */
internal fun inferScrollbackGridSize(
    content: String,
    fallbackColumns: Int = REPLAY_COLUMNS,
    fallbackRows: Int = REPLAY_ROWS,
): ScrollbackGridSize {
    // Markers record the live PTY grid, but a stale default (e.g. 120x32 written before the
    // first real resize) must not win over CUP/CHA evidence from the stream itself. Replaying
    // a 50-row TUI into a 32-row buffer turns every home-repaint into pages of duplicates.
    var markerColumns = 0
    var markerRows = 0
    ScrollbackLayoutMarker.findAll(content).forEach { marker ->
        layoutMarkerGrid(marker)?.let { grid ->
            markerColumns = maxOf(markerColumns, grid.columns)
            markerRows = maxOf(markerRows, grid.rows)
        }
    }

    var maxColumn = 0
    var maxRow = 0
    CursorPosition.findAll(content).forEach { match ->
        val row = match.groupValues[1].toIntOrNull() ?: 1
        val column = match.groupValues[2].toIntOrNull() ?: 1
        maxRow = maxOf(maxRow, row)
        val payloadWidth = visiblePayloadWidth(content, match.range.last + 1)
        maxColumn = maxOf(maxColumn, column + (payloadWidth - 1).coerceAtLeast(0))
    }
    CursorHorizontalAbsolute.findAll(content).forEach { match ->
        val column = match.groupValues[1].toIntOrNull() ?: return@forEach
        val payloadWidth = visiblePayloadWidth(content, match.range.last + 1)
        maxColumn = maxOf(maxColumn, column + (payloadWidth - 1).coerceAtLeast(0))
    }
    val cupColumns = maxColumn.takeIf { it >= MIN_INFERRED_COLUMNS } ?: 0
    val cupRows = maxRow.takeIf { it >= MIN_INFERRED_ROWS } ?: 0
    return ScrollbackGridSize(
        // Prefer the larger of marker vs CUP. A too-small stale marker alone caused
        // full-screen TUI repaints to be mis-merged as pages of duplicated history.
        columns = maxOf(markerColumns, cupColumns).takeIf { it > 0 } ?: fallbackColumns,
        rows = maxOf(markerRows, cupRows).takeIf { it > 0 } ?: fallbackRows,
    )
}

/**
 * Visible cells immediately following an absolute cursor command.
 *
 * This intentionally stops at the next control sequence or line movement. The payloads used
 * for width evidence are ASCII terminal chrome, so code-point count is the correct cell count
 * without paying for a second terminal emulator.
 */
private fun visiblePayloadWidth(content: String, start: Int): Int {
    var index = start
    var width = 0
    while (index < content.length) {
        val char = content[index]
        if (char == '\u001B' || char == '\r' || char == '\n' || char.code < 0x20) break
        if (Character.isHighSurrogate(char) &&
            index + 1 < content.length &&
            Character.isLowSurrogate(content[index + 1])
        ) {
            index += 2
        } else {
            index++
        }
        width++
    }
    return width
}

private val CursorPosition = Regex("\u001B\\[(\\d*);?(\\d*)[Hf]")
private val CursorHorizontalAbsolute = Regex("\u001B\\[(\\d+)G")
private const val MIN_INFERRED_COLUMNS = 40
private const val MIN_INFERRED_ROWS = 10

/** A retained window of raw PTY output positioned within its full stream. */
data class ScrollbackAnsiSnapshot(
    val content: String,
    val startOffset: Long,
    val endOffset: Long,
    val epoch: Long,
    /** Grid active at [endOffset], when known. */
    val columns: Int = 0,
    /** Grid active at [endOffset], when known. */
    val rows: Int = 0,
)

/** Consumer position used to request only the unpersisted suffix of a tee. */
data class ScrollbackAnsiCursor(
    val offset: Long,
    val epoch: Long,
)

/** Terminal layout required to replay absolute cursor addressing faithfully. */
data class ScrollbackGridSize(
    val columns: Int,
    val rows: Int,
)



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
    val seen = LinkedHashSet<String>()
    val captured = mutableListOf<String>()
    BossTermScrollbackReplay(cols, rows).use { replay ->
        var offset = 0
        while (offset < content.length) {
            val end = minOf(offset + chunkSize, content.length)
            replay.feed(content.substring(offset, end))
            for (line in replay.readableLines()) {
                val key = line.trim()
                if (key.isEmpty() || key in seen) continue
                seen += key
                captured += line
            }
            offset = end
        }
    }
    return captured
}

/**
 * Feed the complete raw PTY tee through a fresh BossTerm emulator, sampling styled rows
 * at terminal-aware boundaries. Agent CLIs redraw on the alt screen, which has no native
 * scrollback — the raw tee is the source of truth.
 */
internal fun replayCaptureStyledRows(
    content: String,
    cols: Int = 0,
    rows: Int = 0,
    chunkSize: Int = REPLAY_CAPTURE_CHUNK_SIZE,
): List<StyledTerminalRow> {
    val inferred = inferScrollbackGridSize(content)
    val initialColumns = cols.takeIf { it > 0 } ?: inferred.columns
    val initialRows = rows.takeIf { it > 0 } ?: inferred.rows
    return ScrollbackReplayCapture(initialColumns, initialRows, chunkSize).use { replay ->
        replay.capture(
            ScrollbackAnsiSnapshot(
                content = content,
                startOffset = 0L,
                endOffset = content.length.toLong(),
                epoch = 0L,
                columns = initialColumns,
                rows = initialRows,
            ),
        )
    }
}

/**
 * Incrementally reconstructs styled history from a raw PTY stream via BossTerm.
 */
class ScrollbackReplayCapture(
    cols: Int = REPLAY_COLUMNS,
    rows: Int = REPLAY_ROWS,
    private val chunkSize: Int = REPLAY_CAPTURE_CHUNK_SIZE,
) : AutoCloseable {
    private var currentColumns = cols.coerceAtLeast(1)
    private var currentRows = rows.coerceAtLeast(1)
    private var replay = BossTermScrollbackReplay(currentColumns, currentRows)
    private var captured = ScrollbackAccumulator()
    private var lastOffset = 0L
    private var lastEpoch = 0L

    fun capture(snapshot: ScrollbackAnsiSnapshot): List<StyledTerminalRow> {
        val mustReset = snapshot.epoch != lastEpoch ||
            lastOffset < snapshot.startOffset ||
            lastOffset > snapshot.endOffset
        if (mustReset) {
            reset(
                epoch = snapshot.epoch,
                offset = snapshot.startOffset,
                columns = snapshot.columns.takeIf { it > 0 } ?: currentColumns,
                rows = snapshot.rows.takeIf { it > 0 } ?: currentRows,
            )
        }
        val start = (lastOffset - snapshot.startOffset).toInt()
        val delta = snapshot.content.substring(start)
        val layoutMarkers = ScrollbackLayoutMarker.findAll(delta).toList()
        if (layoutMarkers.isEmpty()) {
            resizeGrid(snapshot.columns, snapshot.rows)
        }
        captureDelta(delta, layoutMarkers)
        lastOffset = snapshot.endOffset
        lastEpoch = snapshot.epoch
        return captured.snapshot()
    }

    private fun captureDelta(content: String, layoutMarkers: List<MatchResult>) {
        if (content.isEmpty()) return
        var offset = 0
        for (marker in layoutMarkers) {
            captureTerminalBytes(content.substring(offset, marker.range.first))
            layoutMarkerGrid(marker)?.let { resizeGrid(it.columns, it.rows) }
            offset = marker.range.last + 1
        }
        captureTerminalBytes(content.substring(offset))
    }

    private fun captureTerminalBytes(content: String) {
        if (content.isEmpty()) return
        for (chunk in replayCaptureChunks(content, chunkSize, currentRows)) {
            replay.feed(chunk)
            captured.merge(replay.styledRows(maxRows = currentRows))
        }
    }

    private fun resizeGrid(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        // Never shrink during replay. Stale `andy-grid` markers (defaults written before the
        // live window resized) would otherwise collapse a tall TUI into a short buffer and
        // duplicate every subsequent home-repaint into history.
        val nextColumns = maxOf(currentColumns, columns)
        val nextRows = maxOf(currentRows, rows)
        if (nextColumns == currentColumns && nextRows == currentRows) return
        currentColumns = nextColumns
        currentRows = nextRows
        replay.resize(nextColumns, nextRows)
    }

    /** Live replay grid; used to detect when a growing stream needs a taller capture. */
    fun gridSize(): ScrollbackGridSize = ScrollbackGridSize(currentColumns, currentRows)

    private fun reset(epoch: Long, offset: Long, columns: Int, rows: Int) {
        runCatching { replay.close() }
        currentColumns = columns.coerceAtLeast(1)
        currentRows = rows.coerceAtLeast(1)
        replay = BossTermScrollbackReplay(currentColumns, currentRows)
        captured = ScrollbackAccumulator()
        lastEpoch = epoch
        lastOffset = offset
    }

    override fun close() {
        runCatching { replay.close() }
    }
}

/** Headless BossTerm emulator used only for raw-tee → transcript derivation. */
internal class BossTermScrollbackReplay(
    cols: Int,
    rows: Int,
) : AutoCloseable {
    private var columns = cols.coerceAtLeast(1)
    private var rowCount = rows.coerceAtLeast(1)
    private val styleState = StyleState()
    private var textBuffer = TerminalTextBuffer(columns, rowCount, styleState, BossTermBackend.DEFAULT_MAX_HISTORY)
    private val display = HeadlessTerminalDisplay(columns, rowCount)
    private var terminal = BossTerminal(display, textBuffer, styleState)

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        val stream = ArrayTerminalDataStream(chunk.toCharArray())
        val emulator = BossEmulator(stream, terminal, allowKittyFileTransfers = false)
        while (emulator.hasNext()) {
            emulator.next()
        }
    }

    fun resize(cols: Int, rows: Int) {
        columns = cols.coerceAtLeast(1)
        rowCount = rows.coerceAtLeast(1)
        runCatching {
            terminal.resize(TermSize(columns, rowCount), RequestOrigin.User)
        }
    }

    fun readableLines(): List<String> = styledRows().map { it.plain }

    fun styledRows(maxRows: Int = 0): List<StyledTerminalRow> {
        val snapshot = textBuffer.createSnapshot()
        val height = snapshot.height
        if (height <= 0) return emptyList()
        // Capture/merge samples must be the visible screen only. Including history lines
        // re-emits already-scrolled rows on every sample and duplicates pages of history.
        if (maxRows > 0) {
            val wanted = minOf(maxRows, height)
            val start = height - wanted
            val rows = ArrayList<StyledTerminalRow>(wanted)
            var row = start
            while (row < height) {
                rows += styledRowFromTerminalLine(snapshot.getLine(row))
                row++
            }
            return rows
        }
        val total = snapshot.historyLinesCount + height
        val rows = ArrayList<StyledTerminalRow>(total)
        var row = -snapshot.historyLinesCount
        while (row < height) {
            rows += styledRowFromTerminalLine(snapshot.getLine(row))
            row++
        }
        return rows
    }

    override fun close() {
        runCatching { terminal.disconnected() }
    }
}

/**
 * Export one emulator line as plain text (for merge/alignment) plus SGR-styled ANSI
 * (for durable `scrollback.ansi` and BossTerm history replay).
 */
internal fun styledRowFromTerminalLine(line: TerminalLine): StyledTerminalRow {
    val plainFull = line.text
    val plain = plainFull.trimEnd()
    if (plain.isEmpty()) return StyledTerminalRow("", "")

    val ansi = StringBuilder(plain.length + 16)
    var emitted = 0
    var lastStyle: TextStyle? = null
    for (entry in line.entries) {
        if (entry == null) continue
        if (emitted >= plain.length) break
        val text = entry.text.toString()
        if (text.isEmpty()) continue
        val remaining = plain.length - emitted
        val piece = if (text.length <= remaining) text else text.substring(0, remaining)
        val style = entry.style
        if (style != lastStyle) {
            ansi.append(textStyleToSgr(style))
            lastStyle = style
        }
        ansi.append(piece)
        emitted += piece.length
    }
    if (lastStyle != null) ansi.append("\u001b[0m")
    return StyledTerminalRow(plain = plain, ansi = ansi.toString())
}

/** CSI SGR for a BossTerm [TextStyle], always starting from a reset for stable row splicing. */
internal fun textStyleToSgr(style: TextStyle): String {
    if (style == TextStyle.EMPTY) return "\u001b[0m"
    val codes = mutableListOf("0")
    if (style.hasOption(TextStyle.Option.BOLD)) codes += "1"
    if (style.hasOption(TextStyle.Option.DIM)) codes += "2"
    if (style.hasOption(TextStyle.Option.ITALIC)) codes += "3"
    if (style.hasOption(TextStyle.Option.UNDERLINED)) codes += "4"
    if (style.hasOption(TextStyle.Option.INVERSE)) codes += "7"
    if (style.hasOption(TextStyle.Option.HIDDEN)) codes += "8"
    appendTerminalColorSgr(codes, style.foreground, foreground = true)
    appendTerminalColorSgr(codes, style.background, foreground = false)
    return "\u001b[${codes.joinToString(";")}m"
}

private fun appendTerminalColorSgr(
    codes: MutableList<String>,
    color: TerminalColor?,
    foreground: Boolean,
) {
    if (color == null) return
    if (color.isIndexed) {
        val index = color.colorIndex
        when (index) {
            in 0..7 -> codes += ((if (foreground) 30 else 40) + index).toString()
            in 8..15 -> codes += ((if (foreground) 90 else 100) + (index - 8)).toString()
            else -> {
                codes += if (foreground) "38" else "48"
                codes += "5"
                codes += index.toString()
            }
        }
        return
    }
    val rgb = runCatching { color.toColor() }.getOrNull() ?: return
    codes += if (foreground) "38" else "48"
    codes += "2"
    codes += rgb.red.toString()
    codes += rgb.green.toString()
    codes += rgb.blue.toString()
}

/**
 * True when a persisted `.ansi` transcript looks like the BossTerm migration bug that
 * wrote plain screen text into both fields (no real SGR) and/or duplicated full frames.
 */
internal fun looksLikeBrokenPlainScrollback(content: String): Boolean {
    if (content.isBlank()) return false
    if (looksLikeRawAnsiTee(content)) return false
    val lines = content.replace("\r\n", "\n").split('\n')
    if (lines.size < 40) {
        // Short files: plain-only (no SGR) after a session that should have had color is suspicious
        // only when we also see extreme repetition.
    }
    val hasRealSgr = SGR_SEQUENCE.containsMatchIn(content)
    val counts = HashMap<String, Int>()
    var maxRepeat = 0
    for (line in lines) {
        val key = stripAnsi(line).trim()
        if (key.length < 12) continue
        val next = (counts[key] ?: 0) + 1
        counts[key] = next
        if (next > maxRepeat) maxRepeat = next
    }
    // Hundreds of identical banners = the alt-screen home-repaint duplication bug.
    if (maxRepeat >= 8) return true
    // Even styled transcripts can keep progressive boot frames / section redraws.
    if (maxRepeat >= 4 && lines.size >= 40) return true
    val antigravityWelcomes = lines.count { isAntigravityWelcomeLine(it) }
    if (antigravityWelcomes >= 2) return true
    // Plain-only long transcripts from agent TUIs lost all styling during migration.
    if (!hasRealSgr && lines.size >= 30 && maxRepeat >= 3) return true
    return false
}

/** Collapse adjacent identical non-blank lines left by older plain exports. */
internal fun collapseRepeatedScrollbackLines(content: String): String {
    if (content.isBlank()) return content.trimEnd()
    val out = ArrayList<String>()
    var prevPlain: String? = null
    for (line in content.replace("\r\n", "\n").split('\n')) {
        val plain = stripAnsi(line).trimEnd()
        if (plain.isNotEmpty() && plain == prevPlain) continue
        out += line
        prevPlain = plain
    }
    return out.joinToString("\n").trimEnd()
}

private val SGR_SEQUENCE = Regex("\u001B\\[[0-9;]*m")

/** Replay grid height: taller than any real viewport so one sequential batch cannot outrun it. */
private const val REPLAY_ROWS = 200

/** Maximum ordinary-output batch between replay samples; full TUI redraws stay atomic. */
private const val REPLAY_CAPTURE_CHUNK_SIZE = 1_024

/** Replay grid width: matches the agent CLI terminal's own default column count. */
private const val REPLAY_COLUMNS = 120


/**
 * Split a raw PTY tee at terminal-aware capture points.
 *
 * Synchronized updates and clear/home redraws are kept whole so replay never samples a
 * half-painted TUI frame. Ordinary streaming output is still sampled in bounded batches,
 * but only after a complete line when possible, so more than one screen cannot disappear
 * between samples.
 */
internal fun replayCaptureChunks(
    content: String,
    maxSequentialChunkSize: Int = REPLAY_CAPTURE_CHUNK_SIZE,
    maxAtomicRedrawLines: Int = REPLAY_ROWS,
): List<String> {
    require(maxSequentialChunkSize > 0)
    require(maxAtomicRedrawLines > 0)
    if (content.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()

    fun addSequential(start: Int, end: Int) {
        var offset = start
        // A terminal keeps the cursor on the next row after a newline, so filling all N rows
        // before sampling can already evict the first visible row. Leave that cursor row free.
        val sequentialRowLimit = (maxAtomicRedrawLines - 1).coerceAtLeast(1)
        while (offset < end) {
            var index = offset
            var lineBreaks = 0
            var lastNewline = -1
            val byteLimit = minOf(offset + maxSequentialChunkSize, end)
            while (index < end) {
                if (content[index] == '\n') {
                    lineBreaks++
                    lastNewline = index
                    if (lineBreaks >= sequentialRowLimit) break
                }
                index++
                if (index >= byteLimit) break
            }
            val split = when {
                index == end -> end
                lineBreaks >= sequentialRowLimit -> index + 1
                lastNewline >= offset -> lastNewline + 1
                else -> index
            }
            chunks += content.substring(offset, split)
            offset = split
        }
    }

    fun addRedraw(start: Int, end: Int) {
        var lineBreaks = 0
        var index = start
        while (index < end && lineBreaks <= maxAtomicRedrawLines) {
            if (content[index] == '\n') lineBreaks++
            index++
        }
        if (lineBreaks <= maxAtomicRedrawLines) {
            chunks += content.substring(start, end)
        } else {
            // A cursor-home sequence followed by more than one replay grid is a stream,
            // not one visible frame. Keep sampling its complete lines so the top cannot
            // scroll away before the next redraw marker.
            addSequential(start, end)
        }
    }

    fun nextRedrawStart(fromIndex: Int): ReplayRedrawBoundary? {
        var index = content.indexOf('\u001B', fromIndex)
        while (index >= 0) {
            when {
                content.startsWith(SYNCHRONIZED_UPDATE_BEGIN, index) ->
                    return ReplayRedrawBoundary(index, synchronized = true, SYNCHRONIZED_UPDATE_BEGIN.length)
                content.startsWith(CLEAR_SCREEN, index) ->
                    return ReplayRedrawBoundary(index, synchronized = false, CLEAR_SCREEN.length)
                content.startsWith(CLEAR_SCROLLBACK, index) ->
                    return ReplayRedrawBoundary(index, synchronized = false, CLEAR_SCROLLBACK.length)
                content.startsWith(CURSOR_HOME, index) ->
                    return ReplayRedrawBoundary(index, synchronized = false, CURSOR_HOME.length)
                content.startsWith(CURSOR_HOME_EXPLICIT, index) ->
                    return ReplayRedrawBoundary(index, synchronized = false, CURSOR_HOME_EXPLICIT.length)
            }
            index = content.indexOf('\u001B', index + 1)
        }
        return null
    }

    var offset = 0
    while (offset < content.length) {
        val boundary = nextRedrawStart(offset)
        if (boundary == null) {
            addSequential(offset, content.length)
            break
        }
        val start = boundary.index
        val synchronized = boundary.synchronized
        if (start > offset) addSequential(offset, start)
        if (synchronized) {
            val endMarker = content.indexOf(
                SYNCHRONIZED_UPDATE_END,
                startIndex = start + SYNCHRONIZED_UPDATE_BEGIN.length,
            )
            if (endMarker < 0) {
                addSequential(start, content.length)
                break
            }
            val end = endMarker + SYNCHRONIZED_UPDATE_END.length
            addRedraw(start, end)
            offset = end
        } else {
            val markerEnd = start + boundary.markerLength
            val next = nextRedrawStart(markerEnd)?.index ?: content.length
            addRedraw(start, next)
            offset = next
        }
    }
    return chunks
}

private const val SYNCHRONIZED_UPDATE_BEGIN = "\u001B[?2026h"
private const val SYNCHRONIZED_UPDATE_END = "\u001B[?2026l"

/** Full-screen redraw starts commonly emitted by terminal UI frameworks. */
private const val CLEAR_SCREEN = "\u001B[2J"
private const val CLEAR_SCROLLBACK = "\u001B[3J"
private const val CURSOR_HOME = "\u001B[H"
private const val CURSOR_HOME_EXPLICIT = "\u001B[1;1H"

private data class ReplayRedrawBoundary(
    val index: Int,
    val synchronized: Boolean,
    val markerLength: Int,
)

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
