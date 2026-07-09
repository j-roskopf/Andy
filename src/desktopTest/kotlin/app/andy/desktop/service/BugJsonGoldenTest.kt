package app.andy.desktop.service

import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugReport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden fixtures captured against the hand-rolled [BugJson] writer before the
 * kotlinx.serialization swap. Fixture files under `golden/` lock the wire format.
 */
class BugJsonGoldenTest {
    private val bugEmoji = "\uD83D\uDC1B"

    private val goldenReport = BugReport(
        id = "bug-golden-1",
        title = "Title with \"quotes\" and \\slashes",
        notes = "Line1\nLine2\tTabbed\rCR",
        deviceSerial = "emulator-5554",
        deviceModel = "Pixel 8",
        apiLevel = null,
        abi = "arm64-v8a",
        resolution = null,
        capturedAtMillis = 10,
        windowStartedAtMillis = 1,
        windowEndedAtMillis = 10,
        actions = listOf(
            BugAction("a1", 2, "input", "Tap \"OK\"", "x=1\ny=2"),
            BugAction("a2", 3, "nav", "Back\\Home", null),
            BugAction("a3", 4, "note", "Unicode café $bugEmoji", "tab\there"),
        ),
        artifacts = listOf(
            BugArtifact("logcat.txt", "logcat.txt", "logcat", 42),
            BugArtifact("capture.mp4", "capture.mp4", "video", null),
        ),
        videoStartedAtMillis = 1,
        videoEndedAtMillis = null,
        videoFrameRate = 15.0,
        videoFrameTimestampsMillis = listOf(1, 4, 9),
    )

    private val expectedReportJson = readGolden("golden/bug_report.json")
    private val expectedActionsJson = readGolden("golden/bug_actions.json")

    @Test
    fun writeReportMatchesGoldenFixture() {
        assertEquals(expectedReportJson, BugJson.writeReport(goldenReport))
    }

    @Test
    fun writeActionsMatchesGoldenFixture() {
        assertEquals(expectedActionsJson, BugJson.writeActions(goldenReport.actions))
    }

    @Test
    fun roundTripPreservesEscapesUnicodeAndNulls() {
        val decoded = BugJson.readReport(BugJson.writeReport(goldenReport))
        assertEquals(goldenReport, decoded)
    }

    @Test
    fun legacyWriterOutputDecodesViaCurrentReader() {
        val legacyJson = LegacyBugJson.writeReport(goldenReport)
        assertEquals(expectedReportJson, legacyJson)
        assertEquals(goldenReport, BugJson.readReport(legacyJson))
    }

    @Test
    fun currentWriterOutputDecodesViaLegacyReader() {
        val currentJson = BugJson.writeReport(goldenReport)
        assertEquals(goldenReport, LegacyBugJson.readReport(currentJson))
    }

    @Test
    fun legacyAndCurrentWritersProduceIdenticalBytes() {
        assertEquals(
            LegacyBugJson.writeReport(goldenReport),
            BugJson.writeReport(goldenReport),
        )
    }

    private fun readGolden(path: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "missing golden fixture $path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
