package app.andy.desktop.service

import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEventSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationLogcatCrashExtractionTest {
    @Test
    fun selectLogLinesForTimelinePrefersFatalOverRecentNoise() {
        val logs = buildList {
            repeat(50) { i ->
                add(TimestampedLogLine(1_000L + i, "07-30 12:00:00.000 1 1 I sensors: tick $i"))
            }
            add(
                TimestampedLogLine(
                    1_050L,
                    "07-30 12:00:00.050 13144 13144 E AndroidRuntime: FATAL EXCEPTION: main",
                ),
            )
            add(
                TimestampedLogLine(
                    1_051L,
                    "07-30 12:00:00.050 13144 13144 E AndroidRuntime: Process: app.andy.inspectordemo, PID: 13144",
                ),
            )
            repeat(200) { i ->
                add(TimestampedLogLine(2_000L + i, "07-30 12:00:01.000 1 1 I sensors: later $i"))
            }
        }
        val selected = selectLogLinesForTimeline(logs, maxLines = 40)
        assertTrue(selected.any { it.line.contains("FATAL EXCEPTION") })
        assertTrue(selected.any { it.line.contains("app.andy.inspectordemo") })
        assertEquals(40, selected.size)
    }

    @Test
    fun extractFatalExceptionsFromLogsBuildsCrashSidecar() {
        val logs = listOf(
            TimestampedLogLine(10L, "07-30 12:17:33.480 13144 13144 E AndroidRuntime: FATAL EXCEPTION: main"),
            TimestampedLogLine(10L, "07-30 12:17:33.480 13144 13144 E AndroidRuntime: Process: app.andy.inspectordemo, PID: 13144"),
            TimestampedLogLine(10L, "07-30 12:17:33.480 13144 13144 E AndroidRuntime: java.lang.IllegalStateException: Java crash from nested caller"),
            TimestampedLogLine(10L, "07-30 12:17:33.480 13144 13144 E AndroidRuntime: \tat app.andy.inspectordemo.Diagnostics.crashFromNestedCaller(Diagnostics.kt:33)"),
            TimestampedLogLine(20L, "07-30 12:17:33.500 1 1 I unrelated: noise"),
        )
        val extracted = extractFatalExceptionsFromLogs(logs)
        assertEquals(1, extracted.size)
        val (record, sidecar) = extracted.single()
        assertEquals("app.andy.inspectordemo", record.packageName)
        assertTrue(record.summary.contains("IllegalStateException"))
        assertTrue(sidecar.stackTrace.contains("crashFromNestedCaller"))
        val event = crashEvent(record, hasSidecar = true)
        assertEquals(InvestigationEventKind.Crash, event.kind)
        assertEquals(InvestigationEventSeverity.Error, event.severity)
    }

    @Test
    fun severityForLogLineDetectsAndroidRuntimeError() {
        assertEquals(
            InvestigationEventSeverity.Error,
            severityForLogLine("07-30 12:17:33.480 13144 13144 E AndroidRuntime: FATAL EXCEPTION: main"),
        )
        assertEquals(
            InvestigationEventSeverity.Info,
            severityForLogLine("07-30 12:17:33.480 1 1 I sensors-hal: tick"),
        )
    }
}
