package app.andy.terminal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawScrollbackFileTest {
    private fun tempFile(name: String = "scrollback.raw") =
        Files.createTempDirectory("andy-raw-scrollback").toFile().resolve(name)

    private fun snapshot(content: String, startOffset: Long = 0L, epoch: Long = 0L) =
        ScrollbackAnsiSnapshot(
            content = content,
            startOffset = startOffset,
            endOffset = startOffset + content.length,
            epoch = epoch,
        )

    @Test
    fun appendsOnlyBytesNotYetWritten() {
        val file = tempFile()
        val raw = RawScrollbackFile(file)

        assertEquals(6, raw.append(snapshot("first\n")))
        // The tee always hands over its whole retained window, so a second flush must
        // contribute only the delta rather than restating what is already on disk.
        assertEquals(7, raw.append(snapshot("first\nsecond\n")))

        assertEquals("first\nsecond\n", file.readText())
    }

    @Test
    fun unchangedSnapshotAppendsNothing() {
        val file = tempFile()
        val raw = RawScrollbackFile(file)
        raw.append(snapshot("only\n"))

        assertEquals(0, raw.append(snapshot("only\n")))
        assertEquals("only\n", file.readText())
    }

    @Test
    fun recordsLayoutInitiallyAndWheneverTheLiveGridChanges() {
        val file = tempFile()
        val raw = RawScrollbackFile(file)

        raw.append(snapshot("wide\n").copy(columns = 164, rows = 54))
        raw.append(snapshot("wide\nmore\n").copy(columns = 164, rows = 54))
        raw.append(snapshot("wide\nmore\nnarrow\n").copy(columns = 100, rows = 32))

        val text = file.readText()
        assertEquals(1, Regex("andy-grid=164x54").findAll(text).count())
        assertEquals(1, Regex("andy-grid=100x32").findAll(text).count())
        assertTrue(text.endsWith("narrow\n"))
    }

    @Test
    fun clearedTeeRestartsWithoutDroppingItsContent() {
        val file = tempFile()
        val raw = RawScrollbackFile(file)
        raw.append(snapshot("before clear\n"))

        // clear() resets the tee's offsets and bumps its epoch; whatever it holds afterwards
        // is new output, not a replay of what we already mirrored.
        assertEquals(12, raw.append(snapshot("after clear\n", startOffset = 0L, epoch = 1L)))
        assertEquals("before clear\nafter clear\n", file.readText())
    }

    @Test
    fun resumesFromOldestRetainedByteWhenTheTeeTrimmedPastUs() {
        val file = tempFile()
        val raw = RawScrollbackFile(file)
        raw.append(snapshot("early\n"))

        // The tee dropped everything up to offset 40 before we flushed again. Those bytes are
        // genuinely gone; the mirror must resume rather than replay the retained window twice.
        val appended = raw.append(snapshot("late\n", startOffset = 40L))

        assertEquals(5, appended)
        assertEquals("early\nlate\n", file.readText())
    }

    @Test
    fun startNewRunDiscardsAnEarlierRunsBytes() {
        val file = tempFile()
        val first = RawScrollbackFile(file)
        first.append(snapshot("previous run\n"))

        val second = RawScrollbackFile(file)
        second.startNewRun()
        second.append(snapshot("this run\n"))

        // The earlier run is already committed to scrollback.ansi, so keeping its raw bytes
        // would derive it a second time.
        assertEquals("this run\n", file.readText())
    }

    @Test
    fun wroteAnythingTracksWhetherTheRunProducedOutput() {
        val file = tempFile()
        val raw = RawScrollbackFile(file)
        assertFalse(raw.wroteAnything)

        raw.append(snapshot(""))
        assertFalse(raw.wroteAnything, "an empty snapshot is not output")

        raw.append(snapshot("something\n"))
        assertTrue(raw.wroteAnything)
    }

    @Test
    fun capDropsOldestCompleteLinesOncePastTheLimit() {
        val file = tempFile()
        val maxBytes = 64 * 1024
        val raw = RawScrollbackFile(file, maxBytes = maxBytes)

        // The cap is soft: trimming only engages past maxBytes plus a megabyte of slack, so
        // the steady state stays append-only. Write comfortably beyond that.
        val lines = (0 until 30_000).map { "line $it".padEnd(63, 'x') + "\n" }
        var offset = 0L
        lines.chunked(2_000).forEach { batch ->
            val text = batch.joinToString("")
            raw.append(snapshot(text, startOffset = offset))
            offset += text.length
        }

        val written = lines.sumOf { it.length }
        val text = file.readText()
        assertTrue(text.length < written / 2, "cap never engaged: kept $text.length of $written")
        assertTrue(text.contains("line 29999"), "newest line must survive")
        assertFalse(text.contains("line 0x"), "oldest line should have been dropped")
        assertTrue(text.startsWith("line "), "must cut on a line boundary, not mid-line")
    }
}
