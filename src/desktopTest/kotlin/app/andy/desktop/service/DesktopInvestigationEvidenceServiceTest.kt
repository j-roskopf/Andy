package app.andy.desktop.service

import app.andy.model.AgentContextualProvenance
import app.andy.model.AndroidDevice
import app.andy.model.BugArtifact
import app.andy.model.BugCaptureDraft
import app.andy.model.BugCaptureStatus
import app.andy.model.BugReport
import app.andy.model.ContextualActionKind
import app.andy.model.EvidenceMaterializeRequest
import app.andy.model.EvidencePreviewRequest
import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEvidenceRef
import app.andy.model.InvestigationInlinePayload
import app.andy.model.InvestigationPayloadRef
import app.andy.model.InvestigationTimeline
import app.andy.service.BugService
import app.andy.service.MirrorFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopInvestigationEvidenceServiceTest {
    private val investigationId = "bug-evidence-1"

    @Test
    fun previewDoesNotWriteAnyFiles() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        val preview = service.preview(
            EvidencePreviewRequest(
                question = "Why did checkout fail?",
                evidence = InvestigationEvidenceRef(investigationId = investigationId, centerMillis = CENTER_MILLIS),
                provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.InvestigateSelection, investigationId = investigationId),
            ),
        )

        assertTrue(preview.artifacts.isNotEmpty())
        assertTrue(preview.promptDraft.contains("Why did checkout fail?"))
        assertFalse(File(home, ".andy/evidence").exists(), "preview must never write to the evidence root")
    }

    @Test
    fun materializeRedactsNetworkHeadersAndTruncatesBody() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        val bundle = service.materialize(
            EvidenceMaterializeRequest(
                evidence = InvestigationEvidenceRef(investigationId = investigationId, centerMillis = CENTER_MILLIS),
                provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.ExplainRequest, investigationId = investigationId),
            ),
        )

        val bundleDir = File(home, bundle.rootRelativePath)
        assertTrue(bundleDir.isDirectory)
        val networkFile = File(bundleDir, "events/network/network-net-1.json")
        assertTrue(networkFile.isFile)
        val networkText = networkFile.readText()
        assertFalse(networkText.contains("secret-token-value"), "auth header must be redacted")
        assertTrue(networkText.contains("[redacted]"))
        assertTrue(bundle.redactionReport.redactedHeaderNames.any { it.equals("Authorization", ignoreCase = true) })
        assertTrue(bundle.manifest.any { it.relativePath == "events/network/network-net-1.json" && it.redacted })
    }

    @Test
    fun materializeRedactsPasswordHierarchyNodes() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        val bundle = service.materialize(
            EvidenceMaterializeRequest(
                evidence = InvestigationEvidenceRef(investigationId = investigationId, centerMillis = CENTER_MILLIS),
                provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.ExplainNode, investigationId = investigationId),
            ),
        )

        val bundleDir = File(home, bundle.rootRelativePath)
        val hierarchyFile = File(bundleDir, "events/hierarchy/hierarchy-1.json")
        assertTrue(hierarchyFile.isFile)
        val hierarchyText = hierarchyFile.readText()
        assertFalse(hierarchyText.contains("hunter2"), "password node text must be redacted")
        assertTrue(bundle.redactionReport.redactedNodeCount >= 1)
    }

    @Test
    fun materializeCapsOversizedCrashText() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        val bundle = service.materialize(
            EvidenceMaterializeRequest(
                evidence = InvestigationEvidenceRef(investigationId = investigationId, centerMillis = CENTER_MILLIS),
                provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.ExplainCrash, investigationId = investigationId),
            ),
        )

        val bundleDir = File(home, bundle.rootRelativePath)
        val crashFile = File(bundleDir, "events/crashes/crash-1.json")
        assertTrue(crashFile.isFile)
        assertTrue(crashFile.length() < LONG_STACK_TRACE.length, "crash text should be truncated below the raw fixture size")
        assertTrue(bundle.manifest.any { it.relativePath == "events/crashes/crash-1.json" && it.redacted })
    }

    @Test
    fun materializeNeverCopiesTheRawVideoAndReferencesItInstead() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        val bundle = service.materialize(
            EvidenceMaterializeRequest(
                evidence = InvestigationEvidenceRef(investigationId = investigationId, centerMillis = CENTER_MILLIS),
                provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.InvestigateSelection, investigationId = investigationId),
            ),
        )

        val bundleDir = File(home, bundle.rootRelativePath)
        bundleDir.walkTopDown().forEach { file ->
            assertFalse(file.name.endsWith(".mp4"), "capture.mp4 must never be copied into the evidence bundle")
        }
        val videoEntry = bundle.manifest.single { it.kind == "video-reference" }
        assertTrue(videoEntry.relativePath.endsWith("capture.mp4"))
        assertTrue(bundle.redactionReport.excludedArtifacts.any { it.contains("capture.mp4") })
    }

    @Test
    fun materializeWritesUnderTheManagedEvidenceRootWithManifestAndRedactionReport() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        val bundle = service.materialize(
            EvidenceMaterializeRequest(
                evidence = InvestigationEvidenceRef(investigationId = investigationId, centerMillis = CENTER_MILLIS),
                provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.InvestigateSelection, investigationId = investigationId),
            ),
        )

        assertTrue(bundle.rootRelativePath.startsWith(".andy/evidence/"))
        val bundleDir = File(home, bundle.rootRelativePath)
        assertTrue(File(bundleDir, "manifest.json").isFile)
        assertTrue(File(bundleDir, "redaction-report.json").isFile)
        assertTrue(File(bundleDir, "timeline-window.json").isFile)
    }

    @Test
    fun materializeThrowsForUnknownInvestigation() = runBlocking {
        val (bugs, home) = fixture()
        val service = DesktopInvestigationEvidenceService(bugs, home)

        var threw = false
        try {
            service.materialize(
                EvidenceMaterializeRequest(
                    evidence = InvestigationEvidenceRef(investigationId = "does-not-exist"),
                    provenance = AgentContextualProvenance(sourceKind = ContextualActionKind.InvestigateSelection),
                ),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "materialize must reject investigation ids it cannot find")
    }

    private fun fixture(): Pair<BugService, File> {
        val home = Files.createTempDirectory("andy-evidence-test").toFile()
        val reportDir = File(home, "reports/$investigationId").apply { mkdirs() }

        File(reportDir, "events/network").apply { mkdirs() }
        File(reportDir, "events/network/network-net-1.json").writeText(
            InvestigationJson.writeNetworkSidecar(
                NetworkEventSidecarDto(
                    exchangeId = "net-1",
                    method = "POST",
                    url = "https://api.example.test/checkout",
                    statusCode = 422,
                    requestHeaders = mapOf("Authorization" to "Bearer secret-token-value", "Content-Type" to "application/json"),
                    responseHeaders = mapOf("Set-Cookie" to "session=abc123"),
                    requestBodyPreview = "x".repeat(5_000),
                    responseBodyPreview = "{\"error\":\"invalid postal code\"}",
                ),
            ),
        )

        File(reportDir, "events/crashes").apply { mkdirs() }
        File(reportDir, "events/crashes/crash-1.json").writeText(
            InvestigationJson.writeCrashSidecar(
                CrashEventSidecarDto(
                    crashId = "c1",
                    kind = "Crash",
                    packageName = "com.example.garden",
                    summary = "NullPointerException",
                    stackTrace = LONG_STACK_TRACE,
                ),
            ),
        )

        File(reportDir, "events/hierarchy").apply { mkdirs() }
        File(reportDir, "events/hierarchy/hierarchy-1.json").writeText(
            InvestigationJson.writeHierarchySidecar(
                HierarchyEventSidecarDto(
                    source = "Uiautomator",
                    packageName = "com.example.garden",
                    displayWidth = 1080,
                    displayHeight = 2400,
                    nodeCount = 2,
                    treeJson = """{"className":"android.widget.EditText","resourceId":"pwd","text":"hunter2","password":true,"children":[]}""",
                ),
            ),
        )

        File(reportDir, "events/screenshots").apply { mkdirs() }
        File(reportDir, "events/screenshots/screenshot-1.png").writeBytes(ByteArray(64) { it.toByte() })

        File(reportDir, "capture.mp4").writeBytes(ByteArray(2_048) { 1 })

        val timeline = InvestigationTimeline(
            originMillis = CENTER_MILLIS - 10_000,
            endedAtMillis = CENTER_MILLIS + 10_000,
            events = listOf(
                InvestigationEvent(
                    id = "network-net-1",
                    atMillis = CENTER_MILLIS - 2_000,
                    kind = InvestigationEventKind.NetworkExchange,
                    summary = "POST /checkout",
                    correlationIds = mapOf("exchangeId" to "net-1"),
                    payloadRef = InvestigationPayloadRef("events/network/network-net-1.json", "network"),
                ),
                InvestigationEvent(
                    id = "crash-1",
                    atMillis = CENTER_MILLIS - 1_000,
                    kind = InvestigationEventKind.Crash,
                    summary = "NullPointerException",
                    correlationIds = mapOf("crashId" to "c1"),
                    payloadRef = InvestigationPayloadRef("events/crashes/crash-1.json", "crash"),
                ),
                InvestigationEvent(
                    id = "hierarchy-1",
                    atMillis = CENTER_MILLIS - 500,
                    kind = InvestigationEventKind.HierarchySnapshot,
                    summary = "Hierarchy captured (start)",
                    payloadRef = InvestigationPayloadRef("events/hierarchy/hierarchy-1.json", "hierarchy"),
                ),
                InvestigationEvent(
                    id = "screenshot-1",
                    atMillis = CENTER_MILLIS,
                    kind = InvestigationEventKind.Screenshot,
                    summary = "Screenshot",
                    payloadRef = InvestigationPayloadRef("events/screenshots/screenshot-1.png", "screenshot", 64L),
                ),
                InvestigationEvent(
                    id = "action-1",
                    atMillis = CENTER_MILLIS + 500,
                    kind = InvestigationEventKind.Action,
                    summary = "Tap Retry",
                    inline = InvestigationInlinePayload(text = "Retry"),
                ),
            ),
        )

        val report = BugReport(
            id = investigationId,
            title = "Checkout failure",
            notes = "",
            deviceSerial = "emulator-5554",
            deviceModel = "Pixel 8",
            apiLevel = "34",
            abi = "arm64-v8a",
            resolution = "1080x2400",
            capturedAtMillis = CENTER_MILLIS,
            windowStartedAtMillis = CENTER_MILLIS - 10_000,
            windowEndedAtMillis = CENTER_MILLIS + 10_000,
            actions = emptyList(),
            artifacts = listOf(
                BugArtifact("capture.mp4", "capture.mp4", "video", 2_048L),
                BugArtifact("timeline.json", "timeline.json", "timeline", 512L),
            ),
            schemaVersion = 2,
            timelineRelativePath = "timeline.json",
        )

        return FakeBugService(report, timeline, reportDir.absolutePath) to home
    }

    private class FakeBugService(
        private val report: BugReport,
        private val timeline: InvestigationTimeline,
        private val reportDirPath: String,
    ) : BugService {
        override val status = flowOf(BugCaptureStatus())
        override suspend fun startCapture(serial: String, device: AndroidDevice?) = Unit
        override suspend fun stopCapture() = Unit
        override suspend fun beginRecording() = Unit
        override fun recordAction(kind: String, label: String, detail: String?) = Unit
        override suspend fun loadBugTimeline(id: String): InvestigationTimeline? = timeline.takeIf { id == report.id }
        override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?): BugReport = report
        override suspend fun saveRecording(device: AndroidDevice?): BugReport = report
        override suspend fun listBugs() = listOf(report)
        override suspend fun listRecordings() = emptyList<BugReport>()
        override suspend fun loadBug(id: String): BugReport? = report.takeIf { it.id == id }
        override suspend fun loadBugLog(id: String) = ""
        override suspend fun deleteBug(id: String) = false
        override suspend fun exportBug(id: String): String? = null
        override fun playbackFrames(id: String, startFrameIndex: Int): Flow<MirrorFrame> = emptyFlow()
        override suspend fun bugVideoFrameCount(id: String) = 0
        override suspend fun loadBugVideoFrame(id: String, frameIndex: Int): MirrorFrame? = null
        override suspend fun bugDirectoryPath(id: String): String? = reportDirPath.takeIf { id == report.id }
    }

    private companion object {
        const val CENTER_MILLIS = 1_000_000L
        val LONG_STACK_TRACE = "at app.example.Foo.bar(Foo.kt:1)\n".repeat(2_000)
    }
}
