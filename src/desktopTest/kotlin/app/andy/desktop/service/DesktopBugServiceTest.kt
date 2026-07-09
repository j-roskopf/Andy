package app.andy.desktop.service

import app.andy.model.AndroidDevice
import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugCaptureDraft
import app.andy.model.BugReport
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.LogcatEntry
import app.andy.model.LogLevel
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.LogcatFilter
import app.andy.service.LogcatService
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        mirror.frames.value = MirrorFrame(16, 16, IntArray(16 * 16) { -1 }, frameNumber = 1)
        logcat.batches.emit(listOf(LogcatEntry("07-07 09:36:39.683", "1234", "1234", LogLevel.Error, "Example", "boom")))
        service.recordAction("input", "Tap 44,55")
        delay(120)

        val report = service.saveBug(BugCaptureDraft("Broken thing", "Tap then boom"), device)
        val reportDir = home.resolve(".andy/bugs/${report.id}")

        assertTrue(reportDir.resolve("metadata.json").isFile)
        assertTrue(reportDir.resolve("actions.json").isFile)
        assertTrue(reportDir.resolve("logcat.txt").readText().contains("Example: boom"))
        assertTrue(reportDir.resolve("capture.mp4").exists())
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
        )

        val decoded = BugJson.readReport(BugJson.writeReport(report))

        assertEquals(report.id, decoded.id)
        assertEquals(report.actions.single().label, decoded.actions.single().label)
        assertEquals(report.artifacts.single().sizeBytes, decoded.artifacts.single().sizeBytes)
        assertEquals(report.videoFrameTimestampsMillis, decoded.videoFrameTimestampsMillis)
    }

    @Test
    fun captureRecordsForegroundScreen() = runBlocking {
        val home = Files.createTempDirectory("andy-bugs-screen-test").toFile()
        val service = DesktopBugService(FakeMirrorEngine(), FakeLogcatService(), home, FakeForegroundDeviceService())

        service.startCapture("emulator-5554", null)
        delay(150)

        val report = service.saveBug(BugCaptureDraft("Screen changed"), null)

        val screen = report.actions.firstOrNull { it.kind == "screen" }
        assertNotNull(screen)
        assertEquals("Screen MainActivity", screen.label)
        assertTrue(screen.detail?.contains("com.example.app/com.example.app.MainActivity") == true)
    }
}

private class FakeMirrorEngine : MirrorEngine {
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(-16777216)))
    override val status = MutableStateFlow("ready")
    override suspend fun connect(serial: String, config: MirrorVideoConfig): CommandResult = CommandResult.success()
    override suspend fun disconnect() = Unit
    override suspend fun sendInput(input: MirrorInput): CommandResult = CommandResult.success()
    override suspend fun screenshot(serial: String): ByteArray? = null
}

private class FakeLogcatService : LogcatService {
    val batches = MutableSharedFlow<List<LogcatEntry>>(replay = 10, extraBufferCapacity = 10)
    override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = batches
    override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int): List<LogcatEntry> = emptyList()
    override suspend fun clear(serial: String) = Unit
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
