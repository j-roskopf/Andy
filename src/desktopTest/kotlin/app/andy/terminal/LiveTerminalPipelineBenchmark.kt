package app.andy.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.BorderLayout
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.RepaintManager
import kotlin.system.measureNanoTime

/**
 * Whole-pipeline benchmark for a *streaming* agent chat, with per-thread attribution.
 *
 * Not a test — run explicitly:
 *   ./gradlew desktopTest --tests "app.andy.terminal.LiveTerminalPipelineBenchmarkTest" \
 *       -Dandy.bench=1 --rerun-tasks
 *
 * [TerminalPipelineBenchmark] deliberately fakes the terminal to isolate the Swing/Metal paint
 * cost. That left everything upstream of the blit unmeasured — PTY reads, the KetraTerm
 * emulator, the scrollback tee, the 4Hz screen scrape, and raw persistence — which is where the
 * remaining streaming CPU has to live. This harness runs the real [KetraTermBackend] against a
 * real PTY child emitting agent-shaped TUI output at a fixed frame rate, hosts its real
 * `SwingTerminal` in a real Compose window, and reports CPU per JVM thread so each stage is
 * attributable rather than inferred.
 *
 * Ablations turn one stage off at a time; the difference from `full` is that stage's cost.
 */
object LiveTerminalPipelineBenchmark {

    private const val WARMUP_SECONDS = 5
    private const val MEASURE_SECONDS = 20

    /** Grid size a maximized Andy chat gets on a large display. */
    private const val COLS = 200
    private const val ROWS = 50

    /** Redraws per second. Agent CLIs sit near 24 while a turn streams. */
    private const val EMIT_FPS = 24

    private val composeDraws = AtomicLong()

    private fun osBean() =
        ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

    /**
     * A PTY child that redraws like an agent TUI.
     *
     * `redraw` repaints the whole grid inside a synchronized-update pair every frame, which is
     * what a full-screen agent CLI does while it streams. `stream` appends new lines and
     * repaints only a status footer, the lighter pattern used for plain scrolling output.
     * Perl is used for sub-second sleep precision without a busy loop stealing the measurement.
     */
    private fun emitterScript(mode: String): File {
        val script = kotlin.io.path.createTempFile("andy-emit", ".pl").toFile()
        script.writeText(
            """
            ${'$'}| = 1;
            my (${'$'}mode, ${'$'}cols, ${'$'}rows, ${'$'}fps, ${'$'}secs) = @ARGV;
            my @spin = ('|', '/', '-', "\\");
            my ${'$'}frames = ${'$'}fps * ${'$'}secs;
            my ${'$'}body = "x" x (${'$'}cols - 20);
            # A TUI hides the hardware cursor once at startup and draws its own prompt block.
            print "\e[?25l" if ${'$'}mode eq 'redraw' || ${'$'}mode eq 'cursor';
            for my ${'$'}i (1 .. ${'$'}frames) {
                if (${'$'}mode eq 'redraw' || ${'$'}mode eq 'cursor') {
                    print "\e[?2026h\e[H";
                    # Agent TUIs hide the cursor once and then re-emit 25h on every spinner
                    # redraw. `sanitizeAgentCliPtyChunk` used to strip exactly this byte string
                    # before the emulator saw it; the `cursor` mode measures what that cost.
                    print "\e[?25h" if ${'$'}mode eq 'cursor';
                    for my ${'$'}r (1 .. ${'$'}rows - 1) {
                        print "\e[K\e[38;5;", (${'$'}r % 256), "mrow ${'$'}r ${'$'}body\e[m\r\n";
                    }
                    print "\e[${'$'}rows;1H\e[K", ${'$'}spin[${'$'}i % 4], " Working (${'$'}i)";
                    print "\e[?2026l";
                } else {
                    print "line ${'$'}i ${'$'}body\r\n";
                    print "\e[s\e[${'$'}rows;1H\e[K", ${'$'}spin[${'$'}i % 4], " Working (${'$'}i)\e[u";
                }
                select(undef, undef, undef, 1.0 / ${'$'}fps);
            }
            """.trimIndent(),
        )
        return script
    }

    private class Variant(
        val name: String,
        val description: String,
        val emitMode: String = "redraw",
        /** Mount the real SwingTerminal in the window. */
        val paint: Boolean = true,
        /** Let the backend's screen scrape run at foreground (250ms) rather than 1s cadence. */
        val foregroundScrape: Boolean = true,
        /** Run the raw-scrollback persistence timer, as AgentTerminalManager does. */
        val persist: Boolean = true,
        /**
         * Repaint cap in fps, or 0 for a stock [RepaintManager].
         *
         * Set per variant by replacing the current manager outright.
         * `TerminalRepaintThrottle.ensureInstalled` is install-once by design, so using it here
         * would leave the throttle in place for every later variant and silently invalidate the
         * comparison.
         */
        val capFps: Int = 15,
        /**
         * Concurrent streaming chats. Andy keeps every live chat's PTY reader, emulator and
         * scrape loop running whether or not it is on screen, so only chat #1 is ever painted;
         * the rest model backgrounded agents and take [backgroundScrape] cadence.
         */
        val chats: Int = 1,
    )

    /**
     * Ordered so drift is measurable, not mistaken for signal.
     *
     * All variants share one JVM, so later ones run against hotter JIT state; the first pass
     * showed a steady ~1.8-point decline per variant that looked like an ablation result. A
     * discarded warmup leads, and `full` is repeated last: the gap between the two `full` runs
     * bounds how much of any difference is ordering rather than the stage being ablated.
     */
    private val variants = listOf(
        Variant("warmup", "Discarded — brings JIT/Metal up before anything is recorded"),
        Variant("full", "Everything on: today's streaming chat"),
        Variant("no-paint", "Widget never mounted — emulator + tee + scrape + persist only", paint = false),
        Variant("cap-off", "Stock RepaintManager: every repaint painted", capFps = 0),
        Variant("cap-60", "Repaint cap at 60fps (the pre-tuning value)", capFps = 60),
        Variant("no-scrape", "Screen scrape at background 1s cadence instead of 250ms", foregroundScrape = false),
        Variant("no-persist", "Raw scrollback persistence timer off", persist = false),
        Variant("stream-mode", "Appending output instead of full-grid redraws", emitMode = "stream"),
        Variant("idle", "Backend up, child emitting nothing (measurement floor)", emitMode = "idle"),
        Variant(
            "bg-1",
            "One backgrounded streaming chat: no paint, 1s scrape",
            paint = false,
            foregroundScrape = false,
        ),
        Variant(
            "bg-4",
            "Four backgrounded streaming chats — tests whether cost is additive",
            paint = false,
            foregroundScrape = false,
            chats = 4,
        ),
        Variant("full-plus-bg3", "One visible chat + 3 backgrounded: the realistic busy case", chats = 4),
        Variant(
            "cursor-25h",
            "Like `full`, but the TUI re-emits show-cursor each frame (sanitizer removed in 4234932)",
            emitMode = "cursor",
        ),
        Variant("full-again", "Identical to `full`; the delta is run-order drift"),
    )

    class ThreadCost(val name: String, val cpuPercent: Double)

    class Result(
        val name: String,
        val description: String,
        val processCpuPercent: Double,
        val javaThreadCpuPercent: Double,
        val composeFps: Double,
        val topThreads: List<ThreadCost>,
    )

    /** CPU time per live JVM thread, keyed by id. Native/GC threads are absent by design. */
    private fun threadCpuNanos(): Map<Long, Pair<String, Long>> {
        val bean = ManagementFactory.getThreadMXBean()
        if (!bean.isThreadCpuTimeSupported) return emptyMap()
        if (!bean.isThreadCpuTimeEnabled) runCatching { bean.isThreadCpuTimeEnabled = true }
        return bean.allThreadIds.toList().mapNotNull { id ->
            val info = bean.getThreadInfo(id) ?: return@mapNotNull null
            val cpu = bean.getThreadCpuTime(id)
            if (cpu < 0) null else id to (info.threadName to cpu)
        }.toMap()
    }

    @Composable
    private fun Host(panel: JPanel) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF12_1214)).drawBehind {
                composeDraws.incrementAndGet()
            },
        ) {
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                background = Color(0xFF12_1214),
                factory = { panel },
            )
        }
    }

    fun run(): List<Result> {
        val results = mutableListOf<Result>()
        for (variant in variants) {
            val result = runCatching { measure(variant) }.getOrElse { failure ->
                println("[live-bench] ${variant.name} failed: $failure")
                Result(variant.name, variant.description, -1.0, -1.0, -1.0, emptyList())
            }
            if (variant.name != "warmup") results += result
            Thread.sleep(1500)
        }
        return results
    }

    private fun measure(variant: Variant): Result {
        onSwingEdt {
            RepaintManager.setCurrentManager(
                if (variant.capFps > 0) {
                    TerminalRepaintThrottle(intervalMillis = 1_000L / variant.capFps)
                } else {
                    RepaintManager()
                },
            )
        }

        val script = emitterScript(variant.emitMode)
        val totalSeconds = WARMUP_SECONDS + MEASURE_SECONDS + 10
        val argv = if (variant.emitMode == "idle") {
            listOf("/bin/sh", "-c", "sleep $totalSeconds")
        } else {
            listOf(
                "/usr/bin/perl", script.absolutePath,
                variant.emitMode, COLS.toString(), ROWS.toString(),
                EMIT_FPS.toString(), totalSeconds.toString(),
            )
        }

        val backends = (0 until variant.chats).map { index ->
            KetraTermBackend(
                sessionId = "bench-${variant.name}-$index",
                cols = COLS,
                rows = ROWS,
                agentCliMode = true,
            ).also { backend ->
                // Only the visible chat gets foreground cadence, exactly as setOnlyForeground does.
                val fg = variant.foregroundScrape && index == 0
                backend.foregroundProvider = { fg }
                backend.start(argv, cwd = System.getProperty("java.io.tmpdir"), env = emptyMap())
            }
        }

        // Mirror AgentTerminalManager's steady-state persistence: append the tee delta to a raw
        // file every 2s, per live chat. This is the only scrollback work the live path does
        // while streaming.
        val rawFiles = backends.map { kotlin.io.path.createTempFile("andy-raw", ".ansi").toFile() }
        val persistThreads = if (variant.persist) {
            backends.zip(rawFiles).mapIndexed { index, (backend, file) ->
                val raw = RawScrollbackFile(file)
                Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        runCatching { Thread.sleep(2_000) }.onFailure { return@Thread }
                        runCatching { raw.append(backend.scrollbackAnsiSnapshot(raw.cursor())) }
                    }
                }.apply { name = "bench-scrollback-persist-$index"; isDaemon = true; start() }
            }
        } else {
            emptyList()
        }

        val panel = JPanel(BorderLayout()).apply { isOpaque = true }
        if (variant.paint) {
            // The widget is created on the EDT inside start(); mount it the way the real
            // surface does, as the sole child of a BorderLayout host inside a SwingPanel.
            onSwingEdt {
                backends.first().swingTerminal()?.let { panel.add(it, BorderLayout.CENTER) }
                panel.revalidate()
            }
        }

        var processCpu = 0.0
        var javaCpu = 0.0
        var composeFps = 0.0
        var top = emptyList<ThreadCost>()

        application(exitProcessOnExit = false) {
            val state = rememberWindowState(width = 1600.dp, height = 1000.dp)
            Window(onCloseRequest = ::exitApplication, state = state, title = variant.name) {
                Host(panel)
                LaunchedEffect(Unit) {
                    delay(WARMUP_SECONDS * 1000L)
                    val os = osBean()
                    val cpu0 = os.processCpuTime
                    val threads0 = threadCpuNanos()
                    composeDraws.set(0)

                    val wall = measureNanoTime { delay(MEASURE_SECONDS * 1000L) }

                    val threads1 = threadCpuNanos()
                    processCpu = 100.0 * (os.processCpuTime - cpu0) / wall
                    composeFps = composeDraws.get() * 1e9 / wall

                    // Attribute by thread name: worker pools are per-task, and a name that
                    // appears twice (skiko, dispatcher) should read as one cost centre.
                    val perName = HashMap<String, Long>()
                    for ((id, after) in threads1) {
                        val before = threads0[id]?.second ?: 0L
                        val delta = after.second - before
                        if (delta <= 0) continue
                        perName.merge(normalizeThreadName(after.first), delta, Long::plus)
                    }
                    javaCpu = 100.0 * perName.values.sum() / wall
                    top = perName.entries
                        .sortedByDescending { it.value }
                        .take(8)
                        .map { ThreadCost(it.key, 100.0 * it.value / wall) }

                    exitApplication()
                }
            }
        }

        persistThreads.forEach { it.interrupt() }
        backends.forEach { runCatching { it.close() } }
        runCatching { script.delete() }
        rawFiles.forEach { runCatching { it.delete() } }

        println(
            "[live-bench] %-12s proc=%5.1f%%  java=%5.1f%%  composeFps=%5.1f".format(
                variant.name, processCpu, javaCpu, composeFps,
            ),
        )
        for (t in top) println("             %-34s %5.1f%%".format(t.name, t.cpuPercent))

        return Result(variant.name, variant.description, processCpu, javaCpu, composeFps, top)
    }

    /** Collapse pool-index suffixes so `DefaultDispatcher-worker-{1..N}` reads as one entry. */
    private fun normalizeThreadName(name: String): String =
        name.replace(Regex("-\\d+$"), "-N")
}
