package app.andy.terminal

import kotlin.test.Test

/**
 * Entry point for [LiveTerminalPipelineBenchmark]. Skipped unless `-Dandy.bench=1`, so a normal
 * `desktopTest` run never spawns PTYs, opens windows, or spends minutes measuring.
 */
class LiveTerminalPipelineBenchmarkTest {
    @Test
    fun benchmark() {
        if (System.getProperty("andy.bench") != "1") return
        val results = LiveTerminalPipelineBenchmark.run()
        println("\n=== live terminal pipeline (streaming chat) ===")
        println("%-13s %8s %8s %11s  %s".format("variant", "proc%", "java%", "composeFps", "what"))
        for (r in results) {
            println(
                "%-13s %7.1f%% %7.1f%% %11.1f  %s".format(
                    r.name, r.processCpuPercent, r.javaThreadCpuPercent, r.composeFps, r.description,
                ),
            )
        }
        println("\n--- per-thread CPU, full pipeline ---")
        results.firstOrNull { it.name == "full" }?.topThreads?.forEach {
            println("%-34s %6.1f%%".format(it.name, it.cpuPercent))
        }
    }
}
