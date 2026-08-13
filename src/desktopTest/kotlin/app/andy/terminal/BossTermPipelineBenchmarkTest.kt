package app.andy.terminal

import kotlin.test.Test

/**
 * Entry point for [BossTermPipelineBenchmark]. Skipped unless `-Dandy.bench=1` is set, so a
 * normal `desktopTest` run never opens windows or spends minutes measuring.
 */
class BossTermPipelineBenchmarkTest {
    @Test
    fun benchmark() {
        if (System.getProperty("andy.bench") != "1") return
        val results = BossTermPipelineBenchmark.run()
        println("\n=== BossTerm Compose pipeline benchmark ===")
        println("%-22s %7s %8s %10s  %s".format("variant", "cpu%", "wall_s", "procCpu_s", "what"))
        for (r in results) {
            println(
                "%-22s %6.1f%% %8.1f %10.2f  %s".format(
                    r.name, r.cpuPercent, r.wallSeconds, r.processCpuSeconds, r.description,
                ),
            )
        }
    }
}
