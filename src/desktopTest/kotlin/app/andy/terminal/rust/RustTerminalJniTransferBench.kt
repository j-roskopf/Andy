package app.andy.terminal.rust

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rough JNI grid-snapshot transfer cost for the Phase-0 go/no-go writeup.
 *
 * Enable with: `-Dandy.rust.term.bench=1`
 */
class RustTerminalJniTransferBench {
    @Test
    fun measureGridSnapshotTransfer() {
        if (System.getProperty("andy.rust.term.bench") != "1") {
            return
        }
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        if (!(os.contains("mac") || os.contains("darwin")) || arch !in setOf("aarch64", "arm64")) {
            return
        }
        assertTrue(RustTerminalNative.isAvailable())

        // Dense-ish agent-CLI-like viewport.
        val cols = 120
        val rows = 40
        RustTerminalEngine(cols, rows).use { engine ->
            val chunk = buildString {
                repeat(rows) { r ->
                    append("\u001B[32mline-$r\u001B[0m ")
                    append("x".repeat(80))
                    append("\r\n")
                }
            }
            engine.advance(chunk)

            // Warmup
            repeat(50) {
                engine.gridChars()
                engine.viewportText()
            }

            val iterations = 500
            val t0 = System.nanoTime()
            var checksum = 0
            repeat(iterations) {
                val grid = engine.gridChars()
                checksum += grid.length
            }
            val gridNs = (System.nanoTime() - t0) / iterations

            val t1 = System.nanoTime()
            repeat(iterations) {
                checksum += engine.viewportText().length
            }
            val textNs = (System.nanoTime() - t1) / iterations

            val report = buildString {
                appendLine("RustTerminal JNI transfer bench")
                appendLine("  grid=${cols}x$rows (${cols * rows} cells)")
                appendLine("  gridChars_avg_us=${gridNs / 1000.0}")
                appendLine("  viewportText_avg_us=${textNs / 1000.0}")
                appendLine("  checksum=$checksum")
            }
            print(report)
            val out = java.io.File("build/rust-terminal-jni-transfer-bench.txt")
            out.parentFile.mkdirs()
            out.writeText(report)
        }
    }
}
