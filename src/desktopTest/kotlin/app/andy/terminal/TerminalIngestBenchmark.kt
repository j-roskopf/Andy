package app.andy.terminal

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession as KetraSession
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets

/**
 * Splits the PTY ingest cost between Andy's scrollback tee and KetraTerm's emulator.
 *
 * `LiveTerminalPipelineBenchmark` shows `terminal-pty-reader` is the largest single thread in a
 * streaming chat, and the largest cost that a *backgrounded* chat still pays. Both the tee and
 * the emulator parse run on that thread, in that order (see [TeeTerminalConnector]), so the
 * whole-pipeline number cannot say which is worth attacking. This feeds an identical corpus
 * through each stage alone and reports cost per megabyte, plus what that works out to at the
 * byte rate a full-grid redraw at 24fps actually produces.
 *
 * Headless and fast — no window, no PTY. Run with:
 *   ./gradlew desktopTest --tests "app.andy.terminal.TerminalIngestBenchmarkTest" \
 *       -Dandy.bench=1 --rerun-tasks
 */
object TerminalIngestBenchmark {

    private const val COLS = 200
    private const val ROWS = 50
    private const val EMIT_FPS = 24

    /** PTY reads arrive in modest chunks, not whole frames; matching that keeps per-call overhead honest. */
    private const val CHUNK = 4096

    class Result(
        val name: String,
        val millisPerMegabyte: Double,
        val percentOfCoreAtStreamRate: Double,
    )

    /** One second of a full-grid redraw at [EMIT_FPS], as the emitter script produces it. */
    private fun oneSecondOfRedraw(): ByteArray {
        val body = "x".repeat(COLS - 20)
        val spin = charArrayOf('|', '/', '-', '\\')
        val text = buildString {
            for (frame in 0 until EMIT_FPS) {
                append("[?2026h[H")
                for (row in 1 until ROWS) {
                    append("[K[38;5;").append(row % 256).append("mrow ")
                        .append(row).append(' ').append(body).append("[m\r\n")
                }
                append("[").append(ROWS).append(";1H[K")
                    .append(spin[frame % 4]).append(" Working (").append(frame).append(')')
                append("[?2026l")
            }
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun cpuNanos(): Long =
        ManagementFactory.getThreadMXBean().currentThreadCpuTime

    private fun measure(name: String, corpus: ByteArray, passes: Int, body: (ByteArray, Int, Int) -> Unit): Result {
        // Warm up JIT on the same code path before the timed passes.
        repeat(2) { feed(corpus, body) }
        val start = cpuNanos()
        repeat(passes) { feed(corpus, body) }
        val cpu = cpuNanos() - start

        val megabytes = passes.toDouble() * corpus.size / (1024 * 1024)
        val millisPerMb = cpu / 1e6 / megabytes
        // Each pass is exactly one second of streaming, so CPU spent per pass *is* the fraction
        // of a core the stage costs while a chat streams at this rate.
        val percentOfCore = 100.0 * cpu / (passes.toDouble() * 1_000_000_000L)
        println("[ingest] %-22s %8.1f ms/MB   %5.1f%% of a core while streaming".format(name, millisPerMb, percentOfCore))
        return Result(name, millisPerMb, percentOfCore)
    }

    private inline fun feed(corpus: ByteArray, body: (ByteArray, Int, Int) -> Unit) {
        var offset = 0
        while (offset < corpus.size) {
            val length = minOf(CHUNK, corpus.size - offset)
            body(corpus, offset, length)
            offset += length
        }
    }

    fun run(): List<Result> {
        val corpus = oneSecondOfRedraw()
        println("[ingest] corpus = ${corpus.size / 1024} KB/s at ${EMIT_FPS}fps, ${COLS}x$ROWS full-grid redraw")
        val passes = 20
        val results = mutableListOf<Result>()

        // The tee alone: UTF-8 carry decode, StringBuilder append, cap trim.
        val tee = ScrollbackAnsiTee()
        results += measure("tee only", corpus, passes) { b, o, l -> tee.append(b, o, l) }

        // The emulator alone.
        val buffer = TerminalBuffers.create(
            width = COLS,
            height = ROWS,
            maxHistory = KetraTermBackend.DEFAULT_MAX_HISTORY,
        )
        val session = KetraSession.create(terminal = buffer, connector = ParkedTerminalConnector())
        session.start(COLS, ROWS)
        results += measure("emulator only", corpus, passes) { b, o, l -> session.onBytes(b, o, l) }

        // Both, in the order TeeTerminalConnector runs them — the real reader-thread cost.
        val tee2 = ScrollbackAnsiTee()
        val buffer2 = TerminalBuffers.create(
            width = COLS,
            height = ROWS,
            maxHistory = KetraTermBackend.DEFAULT_MAX_HISTORY,
        )
        val session2 = KetraSession.create(terminal = buffer2, connector = ParkedTerminalConnector())
        session2.start(COLS, ROWS)
        results += measure("tee + emulator", corpus, passes) { b, o, l ->
            tee2.append(b, o, l)
            session2.onBytes(b, o, l)
        }

        // A SwingTerminal bound to the session but never added to a container — exactly the
        // state of every backgrounded chat, since KetraTermBackend.start always builds the
        // widget. If binding alone costs reader-thread time, invisible chats are paying render
        // bookkeeping for a widget nobody can see.
        val tee3 = ScrollbackAnsiTee()
        val buffer3 = TerminalBuffers.create(
            width = COLS,
            height = ROWS,
            maxHistory = KetraTermBackend.DEFAULT_MAX_HISTORY,
        )
        val session3 = KetraSession.create(terminal = buffer3, connector = ParkedTerminalConnector())
        session3.start(COLS, ROWS)
        val settings = app.andy.model.TerminalAppearanceSnapshot().toAgentCliSwingSettings(
            columns = COLS,
            rows = ROWS,
            scrollbackLines = KetraTermBackend.DEFAULT_MAX_HISTORY,
        )
        val widget = onSwingEdt {
            io.github.ketraterm.ui.swing.api.SwingTerminal(
                settingsProvider = { settings },
                hostServices = andyScrollbackSwingHostServices(),
            ).also { it.bind(session3) }
        }
        results += measure("tee + emu + bound widget", corpus, passes) { b, o, l ->
            tee3.append(b, o, l)
            session3.onBytes(b, o, l)
        }
        runCatching { onSwingEdt { widget.dispose() } }

        // What the 4Hz scrape adds on top, per call, at the same grid size.
        val scrapeStart = cpuNanos()
        val scrapeCalls = 200
        var sink = 0
        repeat(scrapeCalls) { sink += buffer2.getScreenAsString().trimEnd().length }
        val scrapeCpu = cpuNanos() - scrapeStart
        println(
            "[ingest] %-22s %8.3f ms/call  %5.1f%% of a core at 4Hz (sink=%d)".format(
                "screen scrape", scrapeCpu / 1e6 / scrapeCalls,
                100.0 * (scrapeCpu.toDouble() / scrapeCalls) * 4 / 1_000_000_000L, sink,
            ),
        )
        results += Result(
            "screen scrape @4Hz",
            0.0,
            100.0 * (scrapeCpu.toDouble() / scrapeCalls) * 4 / 1_000_000_000L,
        )

        runCatching { session.close() }
        runCatching { session2.close() }
        return results
    }
}
