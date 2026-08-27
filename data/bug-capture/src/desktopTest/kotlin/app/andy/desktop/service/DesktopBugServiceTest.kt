package app.andy.desktop.service

import app.andy.model.AccessibilityNode
import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.AndroidAppDetails
import app.andy.model.AndroidDevice
import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugCaptureDraft
import app.andy.model.BugReport
import app.andy.model.CrashKind
import app.andy.model.CrashRecord
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.HierarchyOptions
import app.andy.model.HierarchySnapshot
import app.andy.model.HierarchySource
import app.andy.model.InvestigationCaptureMode
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationEventSeverity
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.LogcatEntry
import app.andy.model.LogLevel
import app.andy.model.NetworkExchange
import app.andy.model.PerformanceSample
import app.andy.model.ProxyWarning
import app.andy.model.ProxyWarningKind
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.ActionConfigStore
import app.andy.service.AppService
import app.andy.service.CommandResult
import app.andy.service.CrashInspectorService
import app.andy.service.DeviceService
import app.andy.service.EncodedVideoAccessUnit
import app.andy.service.IosTargetRegistry
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.service.MetricsService
import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorEngine
import app.andy.service.MirrorSession
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorStats
import app.andy.service.MirrorVideoConfig
import app.andy.service.ProxyService
import app.andy.service.UnavailableAppService
import app.andy.service.UnavailableCrashInspectorService
import app.andy.service.UnavailableMetricsService
import app.andy.service.UnavailableProxyService
import app.andy.service.ViewHierarchyService
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBugServiceTest {
    @Test
    fun saveListExportAndDeleteBugReport() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-test").toFile()
        val mirror = FakeMirrorEngine()
        val logcat = FakeLogcatService()
        val service = DesktopBugService(mirror, logcat, home)
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Pixel 8",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            apiLevel = "36",
            abi = "arm64-v8a",
            model = "Pixel 8",
            screenSize = "1080x2400",
        )

        service.startCapture(device.serial, device)
        // Width/height below ~64px hit a JavaCV/FFmpeg row-alignment quirk where the decoded
        // frame's computed channel count is wrong (see isPlayableCapture); real device mirrors
        // are always far larger than that, so size this fixture well above the threshold.
        mirror.frames.value = MirrorFrame(96, 96, IntArray(96 * 96) { -1 }, frameNumber = 1)
        logcat.batches.emit(listOf(LogcatEntry("07-07 09:36:39.683", "1234", "1234", LogLevel.Error, "Example", "boom")))
        service.recordAction("input", "Tap 44,55")
        // Poll for the ARGB fallback sampler instead of a fixed delay — a fixed sleep here is
        // exactly the kind of assert that flakes on slower CI runners.
        val sampleDeadline = System.currentTimeMillis() + 15_000
        while (service.status.value.videoFrameCount < 1 && System.currentTimeMillis() < sampleDeadline) delay(20)

        val report = service.saveBug(BugCaptureDraft("Broken thing", "Tap then boom"), device)
        val reportDir = home.resolve(".andy/bugs/${report.id}")

        assertTrue(reportDir.resolve("metadata.json").isFile)
        assertTrue(reportDir.resolve("actions.json").isFile)
        assertTrue(reportDir.resolve("logcat.txt").readText().contains("Example: boom"))
        // A regression here (e.g. a silent encode failure) must not slip through as an
        // existence-only check — assert the video actually contains playable frames.
        assertTrue(reportDir.resolve("capture.mp4").length() > 0L, "capture.mp4 should not be empty")
        assertTrue(service.bugVideoFrameCount(report.id) > 0, "capture.mp4 should have at least one playable frame")
        assertEquals(null, report.videoCaptureWarning)
        assertNotNull(service.loadBugVideoFrame(report.id, 0), "frame 0 should decode")
        // Scrubbing mid-capture must not depend on a fragile packet-copy timebase.
        val lastIndex = (service.bugVideoFrameCount(report.id) - 1).coerceAtLeast(0)
        assertNotNull(service.loadBugVideoFrame(report.id, lastIndex), "last frame should decode via seek or scan")
        assertEquals(listOf(report.id), service.listBugs().map { it.id })
        assertEquals("Broken thing", service.loadBug(report.id)?.title)

        val exportPath = service.exportBug(report.id)
        assertNotNull(exportPath)
        assertTrue(home.resolve(".andy/exports/${report.id}/metadata.json").isFile)

        assertTrue(service.deleteBug(report.id))
        assertTrue(service.listBugs().isEmpty())
    }

    @Test
    fun bugJsonRoundTripsReportActionsAndArtifacts() {
        val report = BugReport(
            id = "bug-1",
            title = "Title",
            notes = "Notes",
            deviceSerial = "serial",
            deviceModel = "model",
            apiLevel = "36",
            abi = "arm64-v8a",
            resolution = "1080x2400",
            capturedAtMillis = 10,
            windowStartedAtMillis = 1,
            windowEndedAtMillis = 10,
            actions = listOf(BugAction("a1", 2, "input", "Back", null)),
            artifacts = listOf(BugArtifact("logcat.txt", "logcat.txt", "logcat", 42)),
            videoStartedAtMillis = 1,
            videoEndedAtMillis = 9,
            videoFrameRate = 15.0,
            videoFrameTimestampsMillis = listOf(1, 4, 9),
            videoCaptureWarning = "No video frames were captured for this device.",
        )

        val decoded = BugJson.readReport(BugJson.writeReport(report))

        assertEquals(report.id, decoded.id)
        assertEquals(report.actions.single().label, decoded.actions.single().label)
        assertEquals(report.artifacts.single().sizeBytes, decoded.artifacts.single().sizeBytes)
        assertEquals(report.videoFrameTimestampsMillis, decoded.videoFrameTimestampsMillis)
        assertEquals(report.videoCaptureWarning, decoded.videoCaptureWarning)
    }

    @Test
    fun missingVideoFramesSurfaceWarningAndLogDiagnostics() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-novideo-test").toFile()
        // Default FakeMirrorEngine exposes only a 1x1 frame and no H.264 units, so no source of
        // video is ever available to the capture buffers below.
        val service = DesktopBugService(FakeMirrorEngine(), FakeLogcatService(), home)

        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured))
        val report = try {
            service.startCapture("emulator-5554", null)
            service.saveBug(BugCaptureDraft("No video available"), null)
        } finally {
            System.setErr(originalErr)
        }

        val captureFile = home.resolve(".andy/bugs/${report.id}/capture.mp4")
        assertEquals(0L, captureFile.length())
        assertNotNull(report.videoCaptureWarning)
        // A save that produces no playable video must never fail silently — it should always be
        // logged so it is diagnosable, which is exactly what went missing before this fix.
        assertTrue(captured.toString().contains("andy-bug:"), "expected a diagnostic log for the missing video")
    }

    @Test
    fun captureRecordsForegroundScreen() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-screen-test").toFile()
        val service = DesktopBugService(FakeMirrorEngine(), FakeLogcatService(), home, FakeForegroundDeviceService())

        service.startCapture("emulator-5554", null)
        // The foreground-screen poll records asynchronously. Wait for the recorded action
        // instead of guessing a fixed delay — 150ms is usually enough locally but not on
        // slower CI runners, which is exactly how this test flaked on macOS CI.
        val deadline = System.currentTimeMillis() + 15_000
        while (service.status.value.actionCount < 1 && System.currentTimeMillis() < deadline) delay(20)

        val report = service.saveBug(BugCaptureDraft("Screen changed"), null)

        val screen = report.actions.firstOrNull { it.kind == "screen" }
        assertNotNull(screen)
        assertEquals("Screen MainActivity", screen.label)
        assertTrue(screen.detail?.contains("com.example.app/com.example.app.MainActivity") == true)
    }

    @Test
    fun mergeH264ConfigKeepsSeparateSpsAndPps() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x0a)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xce.toByte(), 0x06, 0xe2.toByte())
        val merged = DesktopBugService.mergeH264Config(
            DesktopBugService.mergeH264Config(null, sps),
            pps,
        )
        assertTrue(merged.size >= sps.size + pps.size)
        // SPS (type 7) then PPS (type 8) must both survive — overwriting dropped PPS before.
        assertEquals(0x67.toByte(), merged[4])
        assertTrue(
            merged.asList().windowed(5).any {
                it == listOf(0.toByte(), 0.toByte(), 0.toByte(), 1.toByte(), 0x68.toByte())
            },
        )
    }

    @Test
    fun healthyH264StillKeepsSparseArgbSafetyNet() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-h264-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        service.startCapture("emulator-5554", null)
        mirror.frames.value = MirrorFrame(64, 128, IntArray(64 * 128) { -1 }, frameNumber = 1)
        // Annex-B IDR (NAL type 5) — enough for the picture-AU detector.
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0)
        repeat(5) {
            mirror.encodedUnits.emit(
                EncodedVideoAccessUnit(
                    timestampMillis = System.currentTimeMillis(),
                    bytes = idr,
                    width = 64,
                    height = 128,
                ),
            )
            delay(50)
        }
        val sampleDeadline = System.currentTimeMillis() + 15_000
        while (service.status.value.videoFrameCount < 1 && System.currentTimeMillis() < sampleDeadline) delay(20)

        assertTrue(
            service.status.value.videoFrameCount >= 1,
            "bug capture must keep a sparse ARGB safety net even when H.264 units are flowing",
        )
        service.stopCapture()
    }

    @Test
    fun mergeH264ConfigJoinsSeparateSpsAndPpsAccessUnits() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x0a)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xce.toByte(), 0x06, 0xe2.toByte())
        val merged = DesktopBugService.mergeH264Config(
            DesktopBugService.mergeH264Config(null, sps),
            pps,
        )
        assertEquals(sps.size + pps.size, merged.size)
        assertTrue(merged[4].toInt() and 0x1F == 7)
        assertTrue(merged[sps.size + 4].toInt() and 0x1F == 8)
    }

    @Test
    fun separateSpsAndPpsSurviveBeginRecording() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-sps-pps-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        service.startCapture("emulator-5554", null)
        val activeDeadline = System.currentTimeMillis() + 15_000
        while (!service.status.value.active && System.currentTimeMillis() < activeDeadline) delay(20)
        assertTrue(service.status.value.active, "capture must be active before feeding H.264")

        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x0a)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xce.toByte(), 0x06, 0xe2.toByte())
        val mergedSize = sps.size + pps.size
        // Emit both while the collector is subscribed; replay on FakeMirrorEngine covers the
        // race where startCapture's IO job has not entered collect yet.
        assertTrue(
            mirror.encodedUnits.tryEmit(
                EncodedVideoAccessUnit(System.currentTimeMillis(), sps, 64, 128),
            ),
        )
        assertTrue(
            mirror.encodedUnits.tryEmit(
                EncodedVideoAccessUnit(System.currentTimeMillis(), pps, 64, 128),
            ),
        )
        val mergeDeadline = System.currentTimeMillis() + 15_000
        while (service.latestH264ConfigSizeForTest() < mergedSize &&
            System.currentTimeMillis() < mergeDeadline
        ) {
            delay(20)
        }
        assertEquals(
            mergedSize,
            service.latestH264ConfigSizeForTest(),
            "collector must merge separate SPS and PPS access units",
        )

        service.beginRecording()
        assertEquals(
            mergedSize,
            service.latestH264ConfigSizeForTest(),
            "merged SPS+PPS must survive beginRecording",
        )
        service.stopCapture()
    }

    @Test
    fun beginRecordingPreservesH264ConfigForRemux() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-config-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        service.startCapture("emulator-5554", null)
        // Annex-B SPS (NAL type 7) — retained as latestH264Config for remux.
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x0a)
        mirror.encodedUnits.emit(
            EncodedVideoAccessUnit(
                timestampMillis = System.currentTimeMillis(),
                bytes = sps,
                width = 64,
                height = 128,
            ),
        )
        val configDeadline = System.currentTimeMillis() + 15_000
        while (service.latestH264ConfigSizeForTest() < sps.size &&
            System.currentTimeMillis() < configDeadline
        ) {
            delay(20)
        }
        assertEquals(sps.size, service.latestH264ConfigSizeForTest())

        service.beginRecording()
        assertEquals(
            sps.size,
            service.latestH264ConfigSizeForTest(),
            "beginRecording must keep SPS/PPS so later picture AUs remux without 'non-existing PPS'",
        )

        // Picture-only AUs after the reset — mirrors real scrcpy after Record is pressed.
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0)
        repeat(3) {
            mirror.encodedUnits.emit(
                EncodedVideoAccessUnit(
                    timestampMillis = System.currentTimeMillis(),
                    bytes = idr,
                    width = 64,
                    height = 128,
                ),
            )
            delay(30)
        }
        assertEquals(sps.size, service.latestH264ConfigSizeForTest())
        service.stopCapture()
    }

    @Test
    fun recordingKeepsTheFullCaptureAndListsSeparatelyFromBugs() = runBlocking {
        val home = Files.createTempDirectory("andy-recordings-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Pixel 8",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            screenSize = "1080x2400",
        )

        service.startCapture(device.serial, device)
        service.beginRecording()
        service.recordAction("input", "Tap Continue")
        val recording = service.saveRecording(device)

        assertTrue(recording.id.startsWith("recording-"))
        assertEquals("Screen recording", recording.title)
        assertEquals(listOf(recording.id), service.listRecordings().map { it.id })
        assertTrue(service.listBugs().isEmpty())
        assertEquals("Tap Continue", service.loadBug(recording.id)?.actions?.single()?.label)

        home.resolve(".andy/bugs/${recording.id}/capture.mp4").writeBytes(ByteArray(261))
        assertTrue(service.playbackFrames(recording.id).toList().isEmpty())
    }

    @Test
    fun saveInvestigationTimelinePersistsSidecarsAndIdentity() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-investigation-test").toFile()
        val proxy = FakeProxyService()
        val metrics = FakeMetricsService()
        val crashInspector = FakeCrashInspectorService()
        val viewHierarchy = FakeViewHierarchyService()
        val apps = FakeAppService()
        val projectDir = Files.createTempDirectory("andy-bugs-project-test").toFile()
        val workspaceStore = FakeWorkspaceStore(WorkspaceState(lastActionProjectId = "p1"))
        val actionConfig = FakeActionConfigStore(
            ActionsConfig(projects = listOf(ActionProject(id = "p1", name = "Demo", contextDir = projectDir.absolutePath))),
        )
        val networkExchange = NetworkExchange(
            id = "ex1",
            startedAtMillis = System.currentTimeMillis(),
            completedAtMillis = System.currentTimeMillis(),
            method = "GET",
            url = "https://example.test/api",
            statusCode = 200,
            contentType = "application/json",
            sizeBytes = 12,
            durationMillis = 5,
            requestHeaders = mapOf("Accept" to "*/*"),
            responseHeaders = mapOf("Content-Type" to "application/json"),
            requestBodyPreview = null,
            responseBodyPreview = "{}",
            error = null,
            tlsStatus = "tls",
            matchedRuleId = null,
            flowId = "f1",
        )
        proxy.exchangesFlow.value = listOf(networkExchange)
        val service = DesktopBugService(
            FakeMirrorEngine(),
            FakeLogcatService(),
            home,
            proxy = proxy,
            metrics = metrics,
            crashInspector = crashInspector,
            viewHierarchy = viewHierarchy,
            apps = apps,
            workspaceStore = workspaceStore,
            actionConfig = actionConfig,
        )

        service.startCapture("emulator-5554", null)
        val activeDeadline = System.currentTimeMillis() + 15_000
        while (!service.status.value.active && System.currentTimeMillis() < activeDeadline) delay(20)

        // Let the crash poller finish its baseline (empty) seed before injecting crashes.
        delay(InvestigationCrashPollIntervalMillis + 250L)
        crashInspector.crashes = listOf(
            CrashRecord(
                id = "c1",
                kind = CrashKind.JavaCrash,
                packageName = "app.demo",
                timestampMillis = System.currentTimeMillis(),
                summary = "NullPointerException",
            ),
        )
        crashInspector.crashText["c1"] = "full stack trace"

        // Wait for the background collectors/pollers to observe the injected data.
        val ringDeadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < ringDeadline) {
            val sizes = service.investigationRingSizesForTest()
            if ((sizes["network"] ?: 0) > 0 && (sizes["crashes"] ?: 0) > 0 && (sizes["hierarchy"] ?: 0) > 0) break
            delay(50)
        }
        val ringSizes = service.investigationRingSizesForTest()
        assertTrue(
            (ringSizes["network"] ?: 0) > 0 && (ringSizes["crashes"] ?: 0) > 0 && (ringSizes["hierarchy"] ?: 0) > 0,
            "investigation rings not ready before save: $ringSizes",
        )

        val report = service.saveBug(BugCaptureDraft("Investigation save"), null)
        val reportDir = home.resolve(".andy/bugs/${report.id}")

        assertTrue(reportDir.resolve("timeline.json").isFile, "timeline.json should be written")
        assertEquals(2, report.schemaVersion)
        assertEquals(InvestigationJson.TimelineRelativePath, report.timelineRelativePath)
        assertEquals(InvestigationCaptureMode.Rolling, report.captureMode)
        assertEquals("app.demo", report.appIdentity?.packageName)
        assertEquals("1.2.3", report.appIdentity?.versionName)
        assertEquals("p1", report.projectIdentity?.projectId)
        assertNotNull(report.hostIdentity)
        assertTrue(report.artifacts.any { it.name == "timeline.json" })

        val timeline = service.loadBugTimeline(report.id)
        assertNotNull(timeline)
        assertTrue(timeline.events.any { it.kind == InvestigationEventKind.NetworkExchange })
        assertTrue(timeline.events.any { it.kind == InvestigationEventKind.Crash })
        assertTrue(timeline.events.any { it.kind == InvestigationEventKind.HierarchySnapshot })

        assertTrue(reportDir.resolve("events/network").listFiles()?.isNotEmpty() == true, "network sidecar dir should not be empty")
        assertTrue(reportDir.resolve("events/crashes").listFiles()?.isNotEmpty() == true, "crash sidecar dir should not be empty")
        assertTrue(reportDir.resolve("events/hierarchy").listFiles()?.isNotEmpty() == true, "hierarchy sidecar dir should not be empty")

        service.stopCapture()
    }

    @Test
    fun exportInvestigationBundleWritesManifestSummaryAndTimeline() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-bundle-test").toFile()
        val apps = FakeAppService()
        val service = DesktopBugService(FakeMirrorEngine(), FakeLogcatService(), home, apps = apps)

        service.startCapture("emulator-5554", null)
        val activeDeadline = System.currentTimeMillis() + 15_000
        while (!service.status.value.active && System.currentTimeMillis() < activeDeadline) delay(20)
        service.recordAction("input", "Tap 1,1")

        val report = service.saveBug(BugCaptureDraft("Bundle export", "Notes for the bundle"), null)
        val bundlePath = service.exportInvestigationBundle(report.id)
        assertNotNull(bundlePath)
        val bundleDir = java.io.File(bundlePath)

        assertTrue(bundleDir.resolve("manifest.json").isFile, "manifest.json should be written")
        assertTrue(bundleDir.resolve("summary.md").isFile, "summary.md should be written")
        assertTrue(bundleDir.resolve("timeline.json").isFile, "timeline.json should be included in the bundle")
        assertTrue(bundleDir.resolve("actions.json").isFile, "actions.json should be included in the bundle")
        assertTrue(bundleDir.resolve("capture.mp4").isFile, "capture.mp4 should be included in the bundle")

        val manifest = readInvestigationBundleManifest(bundleDir.resolve("manifest.json").readText())
        assertEquals(report.id, manifest.reportId)
        assertEquals(2, manifest.schemaVersion)
        assertTrue(manifest.eventCount > 0)

        val summary = bundleDir.resolve("summary.md").readText()
        assertTrue(summary.contains("Bundle export"))
        assertTrue(summary.contains("Notes for the bundle"))
        assertTrue(summary.contains("Host clock is authoritative"))

        service.stopCapture()
    }

    @Test
    fun exportInvestigationBundleFallsBackToMigrationForLegacyReports() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-bundle-legacy-test").toFile()
        val service = DesktopBugService(FakeMirrorEngine(), FakeLogcatService(), home)

        service.startCapture("emulator-5554", null)
        service.recordAction("input", "Tap 5,5")
        val report = service.saveBug(BugCaptureDraft("Legacy bundle export"), null)

        // Simulate a v1 report saved before timeline.json existed.
        val reportDir = home.resolve(".andy/bugs/${report.id}")
        assertTrue(reportDir.resolve("timeline.json").delete())

        val bundlePath = service.exportInvestigationBundle(report.id)
        assertNotNull(bundlePath)
        val bundleDir = java.io.File(bundlePath)
        assertTrue(bundleDir.resolve("timeline.json").isFile, "a migrated timeline.json should be backfilled")
        assertTrue(bundleDir.resolve("manifest.json").isFile)

        service.stopCapture()
    }

    @Test
    fun recordScreenshotAddsTimelineEvent() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-screenshot-test").toFile()
        val service = DesktopBugService(FakeMirrorEngine(), FakeLogcatService(), home)

        service.startCapture("emulator-5554", null)
        val activeDeadline = System.currentTimeMillis() + 15_000
        while (!service.status.value.active && System.currentTimeMillis() < activeDeadline) delay(20)

        val png = ByteArray(64) { it.toByte() }
        service.recordScreenshot(png, "Screenshot", "before tap")

        val report = service.saveBug(BugCaptureDraft("Screenshot save"), null)
        val reportDir = home.resolve(".andy/bugs/${report.id}")
        val timeline = service.loadBugTimeline(report.id)

        assertNotNull(timeline)
        val screenshotEvent = timeline.events.firstOrNull { it.kind == InvestigationEventKind.Screenshot }
        assertNotNull(screenshotEvent, "screenshot event should be present in the timeline")
        assertEquals("before tap", screenshotEvent.detail)
        val relativePath = screenshotEvent.payloadRef?.relativePath
        assertNotNull(relativePath)
        assertTrue(reportDir.resolve(relativePath).isFile, "screenshot png should be written to disk")

        service.stopCapture()
    }

    @Test
    fun hierarchyCaptureFailureEmitsErrorEvent() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-hierarchy-error-test").toFile()
        val viewHierarchy = FakeViewHierarchyService()
        viewHierarchy.result = Result.failure(IllegalStateException("uiautomator unavailable"))
        val service = DesktopBugService(
            FakeMirrorEngine(),
            FakeLogcatService(),
            home,
            viewHierarchy = viewHierarchy,
        )

        service.startCapture("emulator-5554", null)
        val deadline = System.currentTimeMillis() + 15_000
        while ((service.investigationRingSizesForTest()["hierarchy"] ?: 0) < 1 && System.currentTimeMillis() < deadline) {
            delay(20)
        }

        val report = service.saveBug(BugCaptureDraft("Hierarchy failure save"), null)
        val timeline = service.loadBugTimeline(report.id)

        assertNotNull(timeline)
        val errorEvent = timeline.events.firstOrNull {
            it.kind == InvestigationEventKind.HierarchySnapshot && it.severity == InvestigationEventSeverity.Error
        }
        assertNotNull(errorEvent, "a hierarchy capture failure should surface an error event")
        assertEquals("uiautomator unavailable", errorEvent.detail)

        service.stopCapture()
    }

    @Test
    fun trimDropsOldNetworkAndMetricEventsOutsideWindow() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-trim-test").toFile()
        val proxy = FakeProxyService()
        val metrics = FakeMetricsService()
        val service = DesktopBugService(
            FakeMirrorEngine(),
            FakeLogcatService(),
            home,
            proxy = proxy,
            metrics = metrics,
        )

        service.startCapture("emulator-5554", null)
        val activeDeadline = System.currentTimeMillis() + 15_000
        while (!service.status.value.active && System.currentTimeMillis() < activeDeadline) delay(20)

        // Insert with a *fresh* timestamp first so the automatic trim-on-receipt in the
        // collector doesn't discard it before we can observe it in the ring.
        val now = System.currentTimeMillis()
        proxy.exchangesFlow.value = listOf(
            NetworkExchange(
                id = "fresh",
                startedAtMillis = now,
                completedAtMillis = now,
                method = "GET",
                url = "https://example.test/fresh",
                statusCode = 200,
                contentType = null,
                sizeBytes = null,
                durationMillis = null,
                requestHeaders = emptyMap(),
                responseHeaders = emptyMap(),
                requestBodyPreview = null,
                responseBodyPreview = null,
                error = null,
                tlsStatus = null,
                matchedRuleId = null,
                flowId = "f-fresh",
            ),
        )
        metrics.samples.emit(PerformanceSample(now, 1f, 1f, 1f, 1, null))

        val ringDeadline = System.currentTimeMillis() + 15_000
        while ((service.investigationRingSizesForTest()["network"] ?: 0) < 1 && System.currentTimeMillis() < ringDeadline) {
            delay(20)
        }
        val sizesBeforeTrim = service.investigationRingSizesForTest()
        assertEquals(1, sizesBeforeTrim["network"], "the fresh network exchange should be captured in the ring")

        // Simulate the rolling window elapsing so the previously-fresh event is now stale.
        service.forceTrimForTest(now + 60_000)

        val sizesAfterTrim = service.investigationRingSizesForTest()
        assertEquals(0, sizesAfterTrim["network"], "network exchanges older than the rolling window should be trimmed")

        service.stopCapture()
    }

    @Test
    fun mirrorSessionObserverStartsCaptureWhenAndroidSessionAppears() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-session-start-test").toFile()
        val mirror = FakeMirrorEngine()
        val devices = FakeListDevicesService(
            AndroidDevice(
                serial = "emulator-5554",
                displayName = "Pixel",
                kind = DeviceKind.Emulator,
                state = DeviceConnectionState.Online,
            ),
        )
        val service = DesktopBugService(mirror, FakeLogcatService(), home, devices)

        mirror.session.value = androidMirrorSession("emulator-5554")
        withTimeout(15_000) {
            service.status.first { it.active && it.deviceSerial == "emulator-5554" }
        }

        service.recordAction("input", "Tap from Design")
        assertTrue(service.status.value.actionCount >= 1)

        service.stopCapture()
    }

    @Test
    fun iosMirrorSessionStartsCaptureAndSaveBugFillsDeviceModelFromRegistry() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-ios-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)
        val udid = "CA4B2892-6294-4CD4-AA5A-6031551226BA"
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = udid,
                    displayName = "iPhone 17 Pro",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                    model = "iPhone 17 Pro",
                ),
            ),
        )
        try {
            // iOS mirror sessions have no AndroidDevice; syncCaptureToMirrorSession must still
            // claim them (Phase 1.3) rather than filtering iOS serials out.
            mirror.session.value = androidMirrorSession(udid)
            withTimeout(15_000) {
                service.status.first { it.active && it.deviceSerial == udid }
            }

            val report = service.saveBug(BugCaptureDraft("iOS bug", "notes"), device = null)

            assertEquals(udid, report.deviceSerial)
            assertEquals("iPhone 17 Pro", report.deviceModel)

            service.stopCapture()
        } finally {
            IosTargetRegistry.update(emptyList())
        }
    }

    @Test
    fun mirrorSessionObserverStopsCaptureWhenSessionCleared() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-session-stop-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        mirror.session.value = androidMirrorSession("emulator-5554")
        withTimeout(15_000) {
            service.status.first { it.active }
        }

        mirror.session.value = null
        withTimeout(15_000) {
            service.status.first { !it.active }
        }
        assertEquals("Bug capture idle", service.status.value.message)
    }

    @Test
    fun mirrorSessionStatsUpdatesDoNotRestartCapture() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-session-stats-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        mirror.session.value = androidMirrorSession("emulator-5554")
        withTimeout(15_000) {
            service.status.first { it.active }
        }
        service.recordAction("input", "Keep me")
        val actionsBefore = service.status.value.actionCount

        // Stats-only session copies must not restart capture (distinctUntilChanged on serial).
        mirror.session.value = androidMirrorSession(
            "emulator-5554",
            stats = MirrorStats(displayedFps = 60f, framesPresented = 120),
        )
        delay(200)

        assertTrue(service.status.value.active, "capture should stay active across stats updates")
        assertEquals(
            actionsBefore,
            service.status.value.actionCount,
            "restarting capture would clear the rolling action ring",
        )

        service.stopCapture()
    }

    @Test
    fun mirrorSessionObserverDoesNotStopDuringExplicitRecording() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-session-recording-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        mirror.session.value = androidMirrorSession("emulator-5554")
        withTimeout(15_000) {
            service.status.first { it.active }
        }
        service.beginRecording()
        assertTrue(
            service.status.value.message.startsWith("Recording screen"),
            "beginRecording should switch status into durable Record mode",
        )

        mirror.session.value = null
        delay(300)

        assertTrue(service.status.value.active, "explicit Record mode must survive transient mirror teardown")
        assertTrue(
            service.status.value.message.startsWith("Recording screen"),
            "auto-stop must not clear an in-progress Record session",
        )

        service.stopCapture()
    }

    @Test
    fun rollingCaptureFollowsMirrorVisibility() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-visibility-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        mirror.session.value = androidMirrorSession("emulator-5554")
        withTimeout(15_000) {
            service.status.first { it.active }
        }

        // A warm session with no Live surface on screen must not keep logcat and the pollers alive.
        mirror.presenting.value = false
        withTimeout(15_000) {
            service.status.first { !it.active }
        }

        mirror.presenting.value = true
        withTimeout(15_000) {
            service.status.first { it.active }
        }

        service.stopCapture()
    }

    @Test
    fun explicitCaptureIgnoresMirrorVisibility() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-visibility-explicit-test").toFile()
        val mirror = FakeMirrorEngine()
        val service = DesktopBugService(mirror, FakeLogcatService(), home)

        mirror.presenting.value = false
        mirror.session.value = androidMirrorSession("emulator-5554")
        delay(300)
        assertFalse(service.status.value.active, "a hidden mirror must not start the rolling window")

        // MCP and investigations start capture for a caller that is waiting on the artifacts, so
        // it outlives whatever Live happens to be showing.
        service.startCapture("emulator-5554", null)
        assertTrue(service.status.value.active)
        mirror.presenting.value = true
        delay(200)
        mirror.presenting.value = false
        delay(300)

        assertTrue(service.status.value.active, "an explicit capture must survive a hidden mirror")

        service.stopCapture()
    }
}

private fun androidMirrorSession(
    serial: String,
    stats: MirrorStats = MirrorStats(),
): MirrorSession = MirrorSession(
    serial = serial,
    requestedMode = MirrorRendererMode.Accelerated,
    backend = MirrorBackend(MirrorBackendKind.NativeHardware),
    stats = stats,
)

internal class FakeMirrorEngine : MirrorEngine {
    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(-16777216)))
    override val status = MutableStateFlow("ready")
    override val presenting = MutableStateFlow(true)
    // Replay covers emits that race startCapture's IO collector subscription on slow CI.
    val encodedUnits = MutableSharedFlow<EncodedVideoAccessUnit>(
        replay = 32,
        extraBufferCapacity = 32,
    )
    override val encodedVideo: Flow<EncodedVideoAccessUnit> = encodedUnits
    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult = CommandResult.success()
    override suspend fun disconnect(immediate: Boolean) = Unit
    override suspend fun sendInput(input: MirrorInput): CommandResult = CommandResult.success()
    override suspend fun screenshot(serial: String): ByteArray? = null
}

internal class FakeLogcatService : LogcatService {
    val batches = MutableSharedFlow<List<LogcatEntry>>(replay = 10, extraBufferCapacity = 10)
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = batches
    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry> = emptyList()
    override suspend fun clear(serial: String) = Unit
}

internal class FakeProxyService : ProxyService by UnavailableProxyService {
    val exchangesFlow = MutableStateFlow<List<NetworkExchange>>(emptyList())
    val warningsFlow = MutableStateFlow<List<ProxyWarning>>(emptyList())
    override val exchanges: Flow<List<NetworkExchange>> = exchangesFlow
    override val warnings: Flow<List<ProxyWarning>> = warningsFlow
}

internal class FakeMetricsService : MetricsService by UnavailableMetricsService {
    val samples = MutableSharedFlow<PerformanceSample>(replay = 1, extraBufferCapacity = 16)
    override fun stream(serial: String, packageName: String?): Flow<PerformanceSample> = samples
}

internal class FakeCrashInspectorService : CrashInspectorService by UnavailableCrashInspectorService {
    var crashes: List<CrashRecord> = emptyList()
    val crashText = mutableMapOf<String, String>()
    override suspend fun listCrashes(serial: String): List<CrashRecord> = crashes
    override suspend fun loadCrash(serial: String, id: String): String = crashText[id] ?: ""
}

internal class FakeViewHierarchyService : ViewHierarchyService {
    var result: Result<HierarchySnapshot> = Result.success(
        HierarchySnapshot(
            root = AccessibilityNode(
                id = "root",
                className = "android.widget.FrameLayout",
                packageName = "app.demo",
                resourceId = null,
                text = null,
                contentDescription = null,
                bounds = "[0,0][100,100]",
                clickable = false,
                focusable = false,
                enabled = true,
            ),
            capturedAtMillis = System.currentTimeMillis(),
            displayWidth = 1080,
            displayHeight = 1920,
            source = HierarchySource.Uiautomator,
        ),
    )
    override suspend fun capture(serial: String, options: HierarchyOptions): Result<HierarchySnapshot> = result
}

internal class FakeAppService : AppService by UnavailableAppService {
    override suspend fun focusedPackage(serial: String): String? = "app.demo"
    override suspend fun getAppDetails(serial: String, packageName: String): AndroidAppDetails =
        AndroidAppDetails(versionName = "1.2.3", versionCode = "42", minSdk = "24", targetSdk = "34", debuggable = true)
}

internal class FakeWorkspaceStore(private var value: WorkspaceState) : WorkspaceStore {
    override suspend fun load(): WorkspaceState = value
    override suspend fun save(state: WorkspaceState) {
        this.value = state
    }
}

internal class FakeActionConfigStore(private var config: ActionsConfig) : ActionConfigStore {
    override suspend fun load(): ActionsConfig = config
    override suspend fun save(config: ActionsConfig) {
        this.config = config
    }
}

private class FakeForegroundDeviceService : DeviceService {
    override suspend fun discoverSdk(): SdkDiscovery = SdkDiscovery(null, null, null, null, null)
    override suspend fun listDevices(): List<AndroidDevice> = emptyList()
    override suspend fun shell(serial: String, command: List<String>): CommandResult {
        return when (command) {
            listOf("dumpsys", "activity", "activities") -> CommandResult.success(
                "topResumedActivity=ActivityRecord{abc u0 com.example.app/.MainActivity t1}\n" +
                    "    #0: HomeFragment{abc}\n",
            )
            listOf("dumpsys", "window", "windows") -> CommandResult.success(
                "mCurrentFocus=Window{abc u0 com.example.app/.MainActivity}\n",
            )
            else -> CommandResult.success()
        }
    }
    override suspend fun pair(host: String, port: Int, code: String): CommandResult = CommandResult.failure("Not supported")
    override suspend fun connect(host: String, port: Int): CommandResult = CommandResult.failure("Not supported")
    override suspend fun disconnect(serial: String): CommandResult = CommandResult.failure("Not supported")
    override suspend fun listMdnsServices(): List<app.andy.model.MdnsService> = emptyList()
    override suspend fun mdnsAvailable(): Boolean = false
    override suspend fun generatePairingQr(content: String): ByteArray? = null
}

private class FakeListDevicesService(private vararg val devices: AndroidDevice) : DeviceService {
    override suspend fun discoverSdk(): SdkDiscovery = SdkDiscovery(null, null, null, null, null)
    override suspend fun listDevices(): List<AndroidDevice> = devices.toList()
    override suspend fun shell(serial: String, command: List<String>): CommandResult = CommandResult.success()
    override suspend fun pair(host: String, port: Int, code: String): CommandResult = CommandResult.failure("Not supported")
    override suspend fun connect(host: String, port: Int): CommandResult = CommandResult.failure("Not supported")
    override suspend fun disconnect(serial: String): CommandResult = CommandResult.failure("Not supported")
    override suspend fun listMdnsServices(): List<app.andy.model.MdnsService> = emptyList()
    override suspend fun mdnsAvailable(): Boolean = false
    override suspend fun generatePairingQr(content: String): ByteArray? = null
}
