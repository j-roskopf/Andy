package app.andy.domain

import app.andy.model.LogLevel
import app.andy.model.LogcatEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class StackTraceGroupingTest {
    private fun entry(tag: String, message: String, pid: String? = "1234", level: LogLevel = LogLevel.Error) =
        LogcatEntry(time = "07-29 10:00:00.000", pid = pid, tid = pid, level = level, tag = tag, message = message)

    private data class Case(
        val name: String,
        val entries: List<LogcatEntry>,
        val expectedBlockCount: Int,
        val expectedHeaders: List<String>,
        val expectedFrameCounts: List<Int>,
    )

    @Test
    fun tableDrivenGroupingCases() {
        val cases = listOf(
            Case(
                name = "no crash lines yields no blocks",
                entries = listOf(
                    entry("MainActivity", "onCreate", level = LogLevel.Info),
                    entry("MainActivity", "onResume", level = LogLevel.Info),
                ),
                expectedBlockCount = 0,
                expectedHeaders = emptyList(),
                expectedFrameCounts = emptyList(),
            ),
            Case(
                name = "single fatal exception block with frames",
                entries = listOf(
                    entry("AndroidRuntime", "FATAL EXCEPTION: main"),
                    entry("AndroidRuntime", "java.lang.NullPointerException: boom"),
                    entry("AndroidRuntime", "\tat com.example.Foo.bar(Foo.kt:42)"),
                    entry("AndroidRuntime", "\tat com.example.Main.main(Main.kt:10)"),
                ),
                expectedBlockCount = 1,
                expectedHeaders = listOf("FATAL EXCEPTION: main"),
                expectedFrameCounts = listOf(3),
            ),
            Case(
                name = "bare exception header without FATAL EXCEPTION prefix",
                entries = listOf(
                    entry("MyTag", "java.lang.IllegalStateException: bad state"),
                    entry("MyTag", "\tat com.example.Bar.baz(Bar.kt:5)"),
                ),
                expectedBlockCount = 1,
                expectedHeaders = listOf("java.lang.IllegalStateException: bad state"),
                expectedFrameCounts = listOf(1),
            ),
            Case(
                name = "ANR header starts a block",
                entries = listOf(
                    entry("ActivityManager", "ANR in com.example (com.example/.MainActivity)"),
                    entry("ActivityManager", "Reason: Input dispatching timed out"),
                ),
                expectedBlockCount = 1,
                expectedHeaders = listOf("ANR in com.example (com.example/.MainActivity)"),
                expectedFrameCounts = listOf(1),
            ),
            Case(
                name = "interleaved output from another tag/pid does not fracture the block",
                entries = listOf(
                    entry("AndroidRuntime", "FATAL EXCEPTION: main", pid = "1234"),
                    entry("AndroidRuntime", "java.lang.RuntimeException: oops", pid = "1234"),
                    entry("OtherTag", "unrelated noise", pid = "9999", level = LogLevel.Info),
                    entry("AndroidRuntime", "\tat com.example.Foo.bar(Foo.kt:1)", pid = "1234"),
                ),
                expectedBlockCount = 1,
                expectedHeaders = listOf("FATAL EXCEPTION: main"),
                expectedFrameCounts = listOf(2),
            ),
            Case(
                name = "second fatal exception on same tag/pid starts a new block",
                entries = listOf(
                    entry("AndroidRuntime", "FATAL EXCEPTION: main"),
                    entry("AndroidRuntime", "java.lang.RuntimeException: first"),
                    entry("AndroidRuntime", "\tat com.example.Foo.bar(Foo.kt:1)"),
                    entry("AndroidRuntime", "FATAL EXCEPTION: main"),
                    entry("AndroidRuntime", "java.lang.RuntimeException: second"),
                    entry("AndroidRuntime", "\tat com.example.Foo.baz(Foo.kt:2)"),
                ),
                expectedBlockCount = 2,
                expectedHeaders = listOf("FATAL EXCEPTION: main", "FATAL EXCEPTION: main"),
                expectedFrameCounts = listOf(2, 2),
            ),
            Case(
                name = "header line alone with no follow-up frames yields no block",
                entries = listOf(
                    entry("AndroidRuntime", "FATAL EXCEPTION: main"),
                ),
                expectedBlockCount = 0,
                expectedHeaders = emptyList(),
                expectedFrameCounts = emptyList(),
            ),
            Case(
                // A second crash from a wholly different tag/pid while a block is open cannot
                // be distinguished from unrelated interleaved chatter, so it is swallowed as
                // noise rather than starting (or fracturing) a block — see [groupStackTraces] KDoc.
                name = "crash from a different tag while a block is open is swallowed as noise",
                entries = listOf(
                    entry("TagA", "FATAL EXCEPTION: main", pid = "1"),
                    entry("TagA", "java.lang.RuntimeException: a", pid = "1"),
                    entry("TagA", "\tat com.example.A.a(A.kt:1)", pid = "1"),
                    entry("TagB", "FATAL EXCEPTION: main", pid = "2"),
                    entry("TagB", "java.lang.RuntimeException: b", pid = "2"),
                    entry("TagB", "\tat com.example.B.b(B.kt:2)", pid = "2"),
                ),
                expectedBlockCount = 1,
                expectedHeaders = listOf("FATAL EXCEPTION: main"),
                expectedFrameCounts = listOf(2),
            ),
        )

        for (case in cases) {
            val blocks = groupStackTraces(case.entries)
            assertEquals(case.expectedBlockCount, blocks.size, "block count mismatch for case: ${case.name}")
            assertEquals(case.expectedHeaders, blocks.map { it.header }, "headers mismatch for case: ${case.name}")
            assertEquals(case.expectedFrameCounts, blocks.map { it.frames.size }, "frame counts mismatch for case: ${case.name}")
        }
    }

    @Test
    fun preservesStartAndEndIndexesForSingleBlock() {
        val entries = listOf(
            entry("AndroidRuntime", "FATAL EXCEPTION: main"),
            entry("AndroidRuntime", "java.lang.NullPointerException: boom"),
            entry("AndroidRuntime", "\tat com.example.Foo.bar(Foo.kt:42)"),
        )

        val blocks = groupStackTraces(entries)

        assertEquals(1, blocks.size)
        assertEquals(0, blocks[0].startIndex)
        assertEquals(2, blocks[0].endIndex)
    }

    @Test
    fun causedByAndMoreFramesAreFoldedIntoSameBlock() {
        val entries = listOf(
            entry("AndroidRuntime", "FATAL EXCEPTION: main"),
            entry("AndroidRuntime", "java.lang.RuntimeException: wrapper"),
            entry("AndroidRuntime", "\tat com.example.Foo.bar(Foo.kt:1)"),
            entry("AndroidRuntime", "Caused by: java.lang.NullPointerException: root cause"),
            entry("AndroidRuntime", "\tat com.example.Foo.inner(Foo.kt:2)"),
            entry("AndroidRuntime", "\t... 3 more"),
        )

        val blocks = groupStackTraces(entries)

        assertEquals(1, blocks.size)
        assertEquals(5, blocks[0].frames.size)
    }
}
