package app.andy.desktop.service

import app.andy.model.AppIdentity
import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugReport
import app.andy.model.HostIdentity
import app.andy.model.InvestigationCaptureMode
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEventSeverity
import app.andy.model.InvestigationInlinePayload
import app.andy.model.InvestigationPayloadRef
import app.andy.model.InvestigationReportSchemaVersion
import app.andy.model.InvestigationTimeline
import app.andy.model.ProjectIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvestigationJsonGoldenTest {
    private val goldenTimeline = InvestigationTimeline(
        schemaVersion = 2,
        originMillis = 1,
        endedAtMillis = 10,
        events = listOf(
            InvestigationEvent(
                id = "e1",
                atMillis = 2,
                kind = InvestigationEventKind.Action,
                summary = "Tap OK",
                detail = "10,20",
                correlationIds = mapOf("actionId" to "e1", "legacyKind" to "input"),
                inline = InvestigationInlinePayload(text = "10,20"),
            ),
            InvestigationEvent(
                id = "e2",
                atMillis = 5,
                kind = InvestigationEventKind.NetworkExchange,
                summary = "GET /api",
                severity = InvestigationEventSeverity.Warning,
                correlationIds = mapOf("flowId" to "f1"),
                inline = InvestigationInlinePayload(
                    method = "GET",
                    url = "https://example.test/api",
                    statusCode = 500,
                    durationMillis = 42,
                    proxySessionScoped = true,
                ),
                payloadRef = InvestigationPayloadRef(
                    relativePath = "events/network/e2.json",
                    kind = "network",
                    sizeBytes = 128,
                ),
            ),
            InvestigationEvent(
                id = "e3",
                atMillis = 9,
                kind = InvestigationEventKind.Crash,
                summary = "NullPointerException",
                severity = InvestigationEventSeverity.Error,
                correlationIds = mapOf("crashId" to "c1"),
                inline = InvestigationInlinePayload(
                    packageName = "app.demo",
                    crashKind = "FATAL EXCEPTION",
                ),
                payloadRef = InvestigationPayloadRef(
                    relativePath = "events/crashes/e3.json",
                    kind = "crash",
                ),
            ),
        ),
    )

    @Test
    fun writeTimelineMatchesGoldenFixture() {
        assertEquals(readGolden("golden/investigation_timeline_v2.json"), InvestigationJson.writeTimeline(goldenTimeline))
    }

    @Test
    fun timelineRoundTripPreservesEvents() {
        val decoded = InvestigationJson.readTimeline(InvestigationJson.writeTimeline(goldenTimeline))
        assertEquals(goldenTimeline, decoded)
    }

    @Test
    fun v1BugReportWriteOmitsInvestigationFields() {
        val report = BugReport(
            id = "bug-v1",
            title = "Legacy",
            notes = "",
            deviceSerial = "serial",
            deviceModel = null,
            apiLevel = null,
            abi = null,
            resolution = null,
            capturedAtMillis = 1,
            windowStartedAtMillis = 1,
            windowEndedAtMillis = 1,
            actions = emptyList(),
            artifacts = emptyList(),
            schemaVersion = 1,
        )
        val json = BugJson.writeReport(report)
        assertTrue(!json.contains("schemaVersion"))
        assertTrue(!json.contains("timelineRelativePath"))
        assertTrue(!json.contains("appIdentity"))
        assertEquals(1, BugJson.readReport(json).schemaVersion)
    }

    @Test
    fun v2BugReportRoundTripsIdentityAndTimelinePath() {
        val report = BugReport(
            id = "bug-v2",
            title = "Investigation",
            notes = "n",
            deviceSerial = "emulator-5554",
            deviceModel = "Pixel",
            apiLevel = "34",
            abi = "arm64-v8a",
            resolution = "1080x2400",
            capturedAtMillis = 100,
            windowStartedAtMillis = 1,
            windowEndedAtMillis = 100,
            actions = listOf(BugAction("a1", 10, "input", "Tap")),
            artifacts = listOf(BugArtifact("timeline.json", "timeline.json", "timeline", 12)),
            schemaVersion = InvestigationReportSchemaVersion,
            timelineRelativePath = InvestigationJson.TimelineRelativePath,
            captureMode = InvestigationCaptureMode.Rolling,
            appIdentity = AppIdentity(packageName = "app.demo", versionName = "1.0", debuggable = true),
            projectIdentity = ProjectIdentity(projectId = "p1", gitBranch = "main", gitDirty = false),
            hostIdentity = HostIdentity(andyVersionName = "0.1.0", andyVersionCode = 1, hostOs = "macOS"),
        )
        val decoded = BugJson.readReport(BugJson.writeReport(report))
        assertEquals(report, decoded)
        val legacyWrite = BugJson.writeReport(
            report.copy(schemaVersion = 1, timelineRelativePath = null, captureMode = null),
        )
        assertTrue(!legacyWrite.contains("schemaVersion"))
        assertNull(BugJson.readReport(legacyWrite).timelineRelativePath)
    }

    private fun readGolden(path: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "missing golden fixture $path"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
