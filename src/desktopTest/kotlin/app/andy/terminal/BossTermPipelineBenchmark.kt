package app.andy.terminal

import ai.rever.bossterm.compose.EmbeddableTerminal
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.andy.model.TerminalAppearanceSnapshot
import kotlinx.coroutines.delay
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime

/**
 * Benchmark for the **current** BossTerm Compose terminal pipeline
 * ([BossTermBackend] → [EmbeddableTerminal] → [TerminalFrameLimiter]).
 *
 * The older [TerminalPipelineBenchmark] only exercises a Swing/FakeTerminal stand-in from the
 * KetraTerm era and is not representative of today's CPU cost.
 *
 * Not a unit test — run explicitly:
 * ```
 * ./gradlew desktopTest --tests "app.andy.terminal.BossTermPipelineBenchmarkTest" \
 *     -Dandy.bench=1 --rerun-tasks
 * ```
 *
 * Optional filters:
 * - `-Dandy.bench.fps=15,24,30` — only those FPS caps (`0` = uncapped)
 * - `-Dandy.bench.knobs=1` — also measure `detectFilePaths=false` / `performanceMode=throughput`
 * - `-Dandy.bench.warmupSec=4 -Dandy.bench.measureSec=12` — timing overrides
 */
object BossTermPipelineBenchmark {

    private fun osBean() =
        ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean

    private data class Variant(
        val name: String,
        val description: String,
        /** Value for `andy.terminal.repaint.fps` (`0` = uncapped). */
        val fps: Long,
        val detectFilePaths: Boolean? = null,
        val performanceMode: String? = null,
        /** Override for `andy.terminal.repaint.renderWindowMs`; null keeps display default. */
        val renderWindowMs: Long? = null,
        /** When true, argv is a quiet `cat` instead of the streaming workload. */
        val idle: Boolean = false,
    )

    class Result(
        val name: String,
        val description: String,
        val cpuPercent: Double,
        val wallSeconds: Double,
        val processCpuSeconds: Double,
    )

    private fun configuredFpsList(): List<Long> {
        val raw = System.getProperty("andy.bench.fps")
        if (raw.isNullOrBlank()) return listOf(15L, 24L, 30L, 45L, 0L)
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    private fun variants(): List<Variant> {
        val idle = Variant(
            name = "idle-shell",
            description = "Live PTY (`cat` waiting) with no streaming output — measurement floor",
            fps = 15L,
            idle = true,
            // Pin so production agent defaults cannot leak into the floor measurement.
            detectFilePaths = true,
            performanceMode = "latency",
        )
        // FPS sweep always pins the pre-knob baseline (latency + hyperlinks) so results stay
        // comparable even after production defaults change.
        val fpsVariants = listOf(idle) + configuredFpsList().map { fps ->
            val label = if (fps <= 0L) "uncapped" else "${fps}fps"
            Variant(
                name = label,
                description = if (fps <= 0L) {
                    "Gate disabled; latency + hyperlinks (BossTerm INTERACTIVE path)"
                } else {
                    "Frame cap $fps fps; performanceMode=latency; detectFilePaths=true"
                },
                fps = fps,
                detectFilePaths = true,
                performanceMode = "latency",
            )
        }
        if (System.getProperty("andy.bench.knobs") != "1") return fpsVariants
        return fpsVariants + listOf(
            Variant(
                name = "15fps+noHyperlinks",
                description = "15fps + detectFilePaths=false + latency",
                fps = 15L,
                detectFilePaths = false,
                performanceMode = "latency",
            ),
            Variant(
                name = "15fps+throughput",
                description = "15fps + performanceMode=throughput + hyperlinks",
                fps = 15L,
                detectFilePaths = true,
                performanceMode = "throughput",
            ),
            Variant(
                name = "15fps+both",
                description = "15fps + detectFilePaths=false + performanceMode=throughput",
                fps = 15L,
                detectFilePaths = false,
                performanceMode = "throughput",
            ),
            Variant(
                name = "24fps+both",
                description = "24fps + detectFilePaths=false + performanceMode=throughput",
                fps = 24L,
                detectFilePaths = false,
                performanceMode = "throughput",
            ),
            Variant(
                name = "30fps+both",
                description = "30fps + detectFilePaths=false + performanceMode=throughput",
                fps = 30L,
                detectFilePaths = false,
                performanceMode = "throughput",
            ),
            Variant(
                name = "uncapped+both",
                description = "Uncapped + detectFilePaths=false + performanceMode=throughput",
                fps = 0L,
                detectFilePaths = false,
                performanceMode = "throughput",
            ),
        )
    }

    /**
     * Sustained streaming output sized like a busy agent CLI (~2k redraw-driving chars/sec
     * with newlines + path/URL tokens), not a `yes` firehose. A prior live measurement saw
     * ~2,181 `requestRedraw`/sec; this aims for that order of magnitude so the frame gate —
     * not the emulator parse loop — dominates the A/B.
     *
     * Override with `-Dandy.bench.stream=heavy` for a parse-stress firehose.
     */
    private fun streamCommand(): List<String> {
        val heavy = System.getProperty("andy.bench.stream") == "heavy"
        val script = if (heavy) {
            """
            line=0
            while true; do
              i=0
              while [ ${'$'}i -lt 40 ]; do
                printf 'agent[%05d] working… step=%d path=/Users/joer/Code/Andy/Andy/src/Main.kt url=https://example.com/x\n' ${'$'}line ${'$'}i
                line=${'$'}((line+1))
                i=${'$'}((i+1))
              done
              sleep 0.005
            done
            """.trimIndent()
        } else {
            """
            line=0
            while true; do
              # ~20 lines / 50ms ≈ 400 lines/s ≈ ~2–3k chars/s with the format below
              i=0
              while [ ${'$'}i -lt 20 ]; do
                printf 'agent[%05d] working… step=%d path=/Users/joer/Code/Andy/Andy/src/Main.kt url=https://example.com/x\n' ${'$'}line ${'$'}i
                line=${'$'}((line+1))
                i=${'$'}((i+1))
              done
              sleep 0.05
            done
            """.trimIndent()
        }
        return listOf("/bin/sh", "-c", script)
    }

    @Composable
    private fun TerminalHost(session: BossTermBackend) {
        val override = remember(session) { session.settingsOverride() }
        EmbeddableTerminal(
            state = session.terminalViewState(),
            settingsOverride = override,
            command = session.embedCommand(),
            workingDirectory = session.embedWorkingDirectory(),
            environment = session.embedEnvironment(),
            platformServices = session.platformServices(),
            autoFocus = false,
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun withProp(key: String, value: String?, block: () -> Unit) {
        val previous = System.getProperty(key)
        try {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    private fun runVariant(variant: Variant): Result {
        val warmupSec = System.getProperty("andy.bench.warmupSec")?.toLongOrNull() ?: 5L
        val measureSec = System.getProperty("andy.bench.measureSec")?.toLongOrNull() ?: 15L

        var cpuPercent = 0.0
        var wallSeconds = 0.0
        var processCpuSeconds = 0.0
        var session: BossTermBackend? = null

        withProp("andy.terminal.repaint.fps", variant.fps.toString()) {
            withProp("andy.terminal.repaint.renderWindowMs", variant.renderWindowMs?.toString()) {
                // Always set both knobs when provided so production agent defaults cannot
                // silently alter an isolated A/B cell.
                withProp(
                    "andy.terminal.detectFilePaths",
                    variant.detectFilePaths?.toString(),
                ) {
                    withProp("andy.terminal.performanceMode", variant.performanceMode) {
                        val argv = if (variant.idle) {
                            listOf("/bin/sh", "-c", "cat")
                        } else {
                            streamCommand()
                        }
                        session = TerminalSessions.create(
                            TerminalLaunchRequest(
                                sessionId = "bossterm-bench-${variant.name}-${System.nanoTime()}",
                                argv = argv,
                                cols = 120,
                                rows = 40,
                                mode = TerminalMode.DirectPty,
                                agentCli = true,
                                appearance = TerminalAppearanceSnapshot(),
                            ),
                        ) as BossTermBackend

                        try {
                            application(exitProcessOnExit = false) {
                                val state = rememberWindowState(width = 1280.dp, height = 800.dp)
                                Window(
                                    onCloseRequest = ::exitApplication,
                                    state = state,
                                    title = "BossTerm bench — ${variant.name}",
                                ) {
                                    TerminalHost(session!!)
                                    LaunchedEffect(Unit) {
                                        delay(warmupSec * 1_000L)
                                        val bean = osBean()
                                        val cpu0 = bean.processCpuTime
                                        val wall = measureNanoTime {
                                            delay(measureSec * 1_000L)
                                        }
                                        val cpuDelta = bean.processCpuTime - cpu0
                                        wallSeconds = wall / 1_000_000_000.0
                                        processCpuSeconds = cpuDelta / 1_000_000_000.0
                                        cpuPercent = 100.0 * cpuDelta / wall
                                        exitApplication()
                                    }
                                }
                            }
                        } finally {
                            session?.close()
                        }
                    }
                }
            }
        }

        return Result(
            name = variant.name,
            description = variant.description,
            cpuPercent = cpuPercent,
            wallSeconds = wallSeconds,
            processCpuSeconds = processCpuSeconds,
        )
    }

    fun run(): List<Result> {
        val results = mutableListOf<Result>()
        for (variant in variants()) {
            println("[bossterm-bench] starting ${variant.name} …")
            val result = try {
                runVariant(variant)
            } catch (t: Throwable) {
                println("[bossterm-bench] FAILED ${variant.name}: ${t.message}")
                t.printStackTrace()
                Result(variant.name, "FAILED: ${t.message}", Double.NaN, 0.0, 0.0)
            }
            results += result
            println(
                "[bossterm-bench] %-22s cpu=%5.1f%%  wall=%.1fs  procCpu=%.2fs  %s".format(
                    result.name,
                    result.cpuPercent,
                    result.wallSeconds,
                    result.processCpuSeconds,
                    result.description,
                ),
            )
            Thread.sleep(1_500)
        }
        return results
    }
}
