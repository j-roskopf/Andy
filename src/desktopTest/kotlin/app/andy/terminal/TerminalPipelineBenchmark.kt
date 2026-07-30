package app.andy.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
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
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.RenderingHints
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.measureNanoTime

/**
 * Standalone benchmark for Andy's terminal presentation pipeline.
 *
 * Not a test — run explicitly:
 *   ./gradlew desktopTest --tests "app.andy.terminal.TerminalPipelineBenchmarkTest" \
 *       -Dandy.bench=1 --rerun-tasks
 *
 * Reproduces the SwingPanel-hosted heavyweight terminal widget without KetraTerm or a PTY, so
 * repaint rate and repaint *pattern* are exact inputs rather than whatever an agent CLI emitted.
 * The `throttled` driver replicates [TerminalRepaintThrottle]'s coalescing — an on-demand
 * one-shot Timer — to test whether bursty flushes, rather than repaint volume, are what make
 * the JDK's MTLLayer restart its CVDisplayLink.
 */
object TerminalPipelineBenchmark {

    private const val WARMUP_SECONDS = 6
    private const val MEASURE_SECONDS = 20

    private val composeDraws = AtomicLong()
    private val swingPaints = AtomicLong()

    private fun osBean() =
        java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as com.sun.management.OperatingSystemMXBean

    /**
     * A stand-in for KetraTerm's `SwingTerminal` (also a lightweight `JComponent`): a
     * monospaced grid that dirties one row per tick, as an agent CLI's spinner does.
     */
    private class FakeTerminal : JComponent() {
        private val cols = 200
        private val rows = 50
        private val line = buildString { repeat(cols) { append(('a' + (it % 26))) } }
        private val spinner = charArrayOf('|', '/', '-', '\\')
        var tick = 0

        init {
            isOpaque = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            preferredSize = Dimension(1200, 700)
        }

        val rowHeight: Int get() = getFontMetrics(font).height

        override fun paintComponent(g: Graphics) {
            swingPaints.incrementAndGet()
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            g2.color = java.awt.Color(0x12, 0x12, 0x14)
            g2.fillRect(0, 0, width, height)
            g2.color = java.awt.Color(0xD0, 0xD0, 0xD0)
            val h = g2.fontMetrics.height
            for (r in 0 until rows) {
                val y = (r + 1) * h
                val clip = g2.clipBounds
                if (y < clip.y || y > clip.y + clip.height + h) continue
                g2.drawString(line, 4, y)
            }
            g2.color = java.awt.Color(0x4E, 0xC9, 0xB0)
            g2.drawString("${spinner[tick % 4]} working ($tick)", 4, h)
            g2.dispose()
        }
    }

    /**
     * Byte-for-byte replica of [TerminalRepaintThrottle]'s coalescing strategy, keyed on
     * [FakeTerminal] instead of `SwingTerminal`. Installed globally; inert until [enabled].
     */
    private class BenchThrottle : RepaintManager() {
        private val lock = Any()
        private val pending = LinkedHashMap<JComponent, Rectangle>()
        private var lastFlushNanos = 0L

        @Volatile var enabled = false

        @Volatile var intervalMillis = 50L

        private val timer = Timer(50) { flush() }.apply {
            isRepeats = false
            isCoalesce = true
        }

        override fun addDirtyRegion(c: JComponent, x: Int, y: Int, w: Int, h: Int) {
            if (!enabled || w <= 0 || h <= 0 || c !is FakeTerminal) {
                super.addDirtyRegion(c, x, y, w, h)
                return
            }
            val dueInMillis: Long
            synchronized(lock) {
                pending.merge(c, Rectangle(x, y, w, h)) { a, b -> a.union(b) }
                val since = (System.nanoTime() - lastFlushNanos) / 1_000_000L
                dueInMillis = (intervalMillis - since).coerceAtLeast(0L)
            }
            if (dueInMillis == 0L) {
                if (SwingUtilities.isEventDispatchThread()) flush() else SwingUtilities.invokeLater(::flush)
            } else {
                SwingUtilities.invokeLater {
                    if (!timer.isRunning) {
                        timer.initialDelay = dueInMillis.toInt()
                        timer.start()
                    }
                }
            }
        }

        private fun flush() {
            val batch: List<Map.Entry<JComponent, Rectangle>>
            synchronized(lock) {
                if (pending.isEmpty()) return
                batch = pending.entries.toList()
                pending.clear()
                lastFlushNanos = System.nanoTime()
            }
            for ((c, r) in batch) super.addDirtyRegion(c, r.x, r.y, r.width, r.height)
        }
    }

    private val throttle = BenchThrottle()

    private class Variant(
        val name: String,
        val description: String,
        /** Rate at which repaints are *requested*. 0 = none. */
        val inputFps: Int,
        /** When non-zero, requests go through the throttle replica capped at this rate. */
        val capFps: Int = 0,
    )

    private val variants = listOf(
        Variant("steady@20", "Repaints every 50ms, unthrottled (today's effective rate)", 20),
        Variant("steady@15", "Repaints every 67ms, unthrottled (proposed cap)", 15),
        Variant("throttled@24>20", "24/s input coalesced by the throttle replica to 20/s", 24, 20),
        Variant("throttled@24>15", "24/s input coalesced by the throttle replica to 15/s", 24, 15),
        Variant("blink@600ms", "Cursor-blink cadence only (1.67/s), no streaming output", 0),
        Variant("idle", "Nothing repainting (measurement floor)", -1),
    )

    class Result(
        val name: String,
        val description: String,
        val cpuPercent: Double,
        val swingFps: Double,
        val composeFps: Double,
        val displayLinkChurn: Double,
    )

    /**
     * Distinct `CVDisplayLink` threads created per second, via `sample`. The JDK's MTLLayer
     * starts a display link when layer content changes and tears it down once it settles, so
     * this rate reads out how often the layer is being restarted.
     */
    private fun measureDisplayLinkChurn(): Double {
        val out = kotlin.io.path.createTempFile("dlchurn", ".txt").toFile()
        val seconds = 5
        return runCatching {
            ProcessBuilder(
                "sample", ProcessHandle.current().pid().toString(),
                seconds.toString(), "-f", out.absolutePath,
            ).redirectErrorStream(true).start().waitFor()
            Regex("Thread_(\\d+): CVDisplayLink")
                .findAll(out.readText())
                .map { it.groupValues[1] }
                .toSet().size.toDouble() / seconds
        }.also { out.delete() }.getOrDefault(-1.0)
    }

    @Composable
    private fun SwingClean(terminal: FakeTerminal) {
        Box(Modifier.fillMaxSize().background(Color(0xFF12_1214)).drawBehind {
            composeDraws.incrementAndGet()
        }) {
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                background = Color(0xFF12_1214),
                factory = { JPanel(BorderLayout()).apply { isOpaque = true; add(terminal) } },
            )
        }
    }

    fun run(): List<Result> {
        SwingUtilities.invokeAndWait { RepaintManager.setCurrentManager(throttle) }
        val results = mutableListOf<Result>()

        for (variant in variants) {
            val terminal = FakeTerminal()
            throttle.enabled = variant.capFps > 0
            if (variant.capFps > 0) throttle.intervalMillis = (1000L / variant.capFps)

            // "blink" models KetraTerm's 600ms cursor timer; negative fps means no driver.
            val periodMillis = when {
                variant.inputFps > 0 -> 1000 / variant.inputFps
                variant.inputFps == 0 -> 600
                else -> 0
            }
            var driver: Timer? = null
            if (periodMillis > 0) {
                driver = Timer(periodMillis) {
                    terminal.tick++
                    terminal.repaint(0, 0, terminal.width, terminal.rowHeight + 4)
                }
                driver.start()
            }

            composeDraws.set(0)
            swingPaints.set(0)
            var cpu = 0.0
            var sFps = 0.0
            var cFps = 0.0
            var churn = -1.0

            application(exitProcessOnExit = false) {
                val state = rememberWindowState(width = 1600.dp, height = 1000.dp)
                Window(onCloseRequest = ::exitApplication, state = state, title = variant.name) {
                    SwingClean(terminal)
                    LaunchedEffect(Unit) {
                        delay(WARMUP_SECONDS * 1000L)
                        val bean = osBean()
                        val cpu0 = bean.processCpuTime
                        composeDraws.set(0)
                        swingPaints.set(0)
                        val wall = measureNanoTime { delay(MEASURE_SECONDS * 1000L) }
                        cpu = 100.0 * (bean.processCpuTime - cpu0) / wall
                        sFps = swingPaints.get() * 1e9 / wall
                        cFps = composeDraws.get() * 1e9 / wall
                        churn = measureDisplayLinkChurn()
                        exitApplication()
                    }
                }
            }

            driver?.stop()
            throttle.enabled = false
            results += Result(variant.name, variant.description, cpu, sFps, cFps, churn)
            println(
                "[bench] %-16s cpu=%5.1f%%  swingFps=%5.1f  composeFps=%5.1f  churn=%5.1f/s".format(
                    variant.name, cpu, sFps, cFps, churn,
                ),
            )
            Thread.sleep(1500)
        }
        return results
    }
}
