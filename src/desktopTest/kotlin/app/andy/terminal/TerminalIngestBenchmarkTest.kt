package app.andy.terminal

import kotlin.test.Test

/** Entry point for [TerminalIngestBenchmark]. Skipped unless `-Dandy.bench=1`. */
class TerminalIngestBenchmarkTest {
    @Test
    fun benchmark() {
        if (System.getProperty("andy.bench") != "1") return
        val results = TerminalIngestBenchmark.run()
        println("\n=== PTY ingest cost split ===")
        for (r in results) {
            println("%-22s %5.1f%% of a core".format(r.name, r.percentOfCoreAtStreamRate))
        }
    }
}
