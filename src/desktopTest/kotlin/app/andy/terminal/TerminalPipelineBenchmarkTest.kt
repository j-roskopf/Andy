package app.andy.terminal

import kotlin.test.Test

/**
 * Entry point for [TerminalPipelineBenchmark]. Skipped unless `-Dandy.bench=1` is set, so a
 * normal `desktopTest` run never opens windows or spends minutes measuring.
 */
class TerminalPipelineBenchmarkTest {
    @Test
    fun benchmark() {
        if (System.getProperty("andy.bench") != "1") return
        val results = TerminalPipelineBenchmark.run()
        println("\n=== terminal pipeline benchmark ===")
        println("%-17s %7s %10s %11s %9s  %s".format("variant", "cpu%", "swingFps", "composeFps", "churn/s", "what"))
        for (r in results) {
            println(
                "%-17s %6.1f%% %10.1f %11.1f %9.1f  %s".format(
                    r.name, r.cpuPercent, r.swingFps, r.composeFps, r.displayLinkChurn, r.description,
                ),
            )
        }
    }
}
