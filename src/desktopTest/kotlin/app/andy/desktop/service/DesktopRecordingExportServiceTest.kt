package app.andy.desktop.service

import app.andy.model.AndroidDevice
import app.andy.model.ClipFormat
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.RecordingExportRequest
import app.andy.service.MirrorFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the real FFmpeg/JavaCV-backed [DesktopRecordingExportService] end to end against a
 * synthetic capture (§E.4) — same local encode/decode style as [DesktopBugServiceTest], no
 * device or network required, so these are not gated behind an opt-in env var.
 */
class DesktopRecordingExportServiceTest {
    @Test
    fun exportsTrimmedGifFromSavedRecording() = runBlocking {
        val (bugs, report) = savedRecordingWithFrames("andy-export-gif-test")
        val service = DesktopRecordingExportService(bugs)
        val target = Files.createTempFile("andy-export", ".gif").toFile().apply { delete() }

        val result = service.export(
            RecordingExportRequest(
                id = report.id,
                startMillis = report.videoStartedAtMillis!!,
                endMillis = report.videoEndedAtMillis!!,
                format = ClipFormat.Gif,
                scale = 64,
                fps = 8,
                loop = true,
            ),
            target.absolutePath,
        )

        val clip = result.getOrThrow()
        assertEquals(ClipFormat.Gif, clip.format)
        assertTrue(target.length() > 0L, "exported GIF should not be empty")
        assertEquals(clip.sizeBytes, target.length())
        assertTrue(clip.frameCount > 0)
        assertTrue(clip.widthPx > 0 && clip.heightPx > 0)
    }

    @Test
    fun exportsPngSequenceFromSavedRecording() = runBlocking {
        val (bugs, report) = savedRecordingWithFrames("andy-export-png-test")
        val service = DesktopRecordingExportService(bugs)
        val targetDir = Files.createTempDirectory("andy-export-png").toFile()

        val result = service.export(
            RecordingExportRequest(
                id = report.id,
                startMillis = report.videoStartedAtMillis!!,
                endMillis = report.videoEndedAtMillis!!,
                format = ClipFormat.PngSequence,
                scale = 64,
                fps = 8,
            ),
            targetDir.absolutePath,
        )

        val clip = result.getOrThrow()
        assertEquals(ClipFormat.PngSequence, clip.format)
        val pngFiles = targetDir.listFiles { file -> file.extension.equals("png", ignoreCase = true) }.orEmpty()
        assertTrue(pngFiles.isNotEmpty(), "expected at least one exported PNG frame")
        assertEquals(clip.frameCount, pngFiles.size)
    }

    @Test
    fun exportsTrimmedMp4FromSavedRecording() = runBlocking {
        val (bugs, report) = savedRecordingWithFrames("andy-export-mp4-test")
        val service = DesktopRecordingExportService(bugs)
        val target = Files.createTempFile("andy-export", ".mp4").toFile().apply { delete() }

        val result = service.export(
            RecordingExportRequest(
                id = report.id,
                startMillis = report.videoStartedAtMillis!!,
                endMillis = report.videoEndedAtMillis!!,
                format = ClipFormat.Mp4,
                scale = 64,
            ),
            target.absolutePath,
        )

        val clip = result.getOrThrow()
        assertEquals(ClipFormat.Mp4, clip.format)
        assertTrue(target.length() > 0L, "exported MP4 should not be empty")
    }

    @Test
    fun exportRejectsOutOfRangeTrim() = runBlocking {
        val (bugs, report) = savedRecordingWithFrames("andy-export-validate-test")
        val service = DesktopRecordingExportService(bugs)
        val target = Files.createTempFile("andy-export", ".gif").toFile().apply { delete() }

        val result = service.export(
            RecordingExportRequest(
                id = report.id,
                startMillis = (report.videoStartedAtMillis ?: 0L) - 10_000L,
                endMillis = report.videoEndedAtMillis ?: 0L,
                format = ClipFormat.Gif,
            ),
            target.absolutePath,
        )

        assertTrue(result.isFailure)
        assertFalse(target.exists(), "a rejected export must not write partial output")
    }

    /**
     * WebP depends on `libwebp` being compiled into the bundled FFmpeg build; accept either a
     * successful export or the explicit "libwebp may be unavailable" failure rather than gating
     * the whole test behind an opt-in env var.
     */
    @Test
    fun exportsWebpWhenLibwebpIsAvailable() = runBlocking {
        val (bugs, report) = savedRecordingWithFrames("andy-export-webp-test")
        val service = DesktopRecordingExportService(bugs)
        val target = Files.createTempFile("andy-export", ".webp").toFile().apply { delete() }

        val result = service.export(
            RecordingExportRequest(
                id = report.id,
                startMillis = report.videoStartedAtMillis!!,
                endMillis = report.videoEndedAtMillis!!,
                format = ClipFormat.WebP,
                scale = 64,
                fps = 8,
            ),
            target.absolutePath,
        )

        result.fold(
            onSuccess = { clip ->
                assertEquals(ClipFormat.WebP, clip.format)
                assertTrue(target.length() > 0L)
            },
            onFailure = { error ->
                assertTrue(
                    error.message?.contains("webp", ignoreCase = true) == true,
                    "unexpected WebP export failure: ${error.message}",
                )
            },
        )
    }

    private suspend fun savedRecordingWithFrames(tempPrefix: String): Pair<DesktopBugService, app.andy.model.BugReport> {
        val home = Files.createTempDirectory(tempPrefix).toFile()
        val mirror = FakeMirrorEngine()
        val bugs = DesktopBugService(mirror, FakeLogcatService(), home)
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Pixel 8",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            screenSize = "1080x2400",
        )

        bugs.startCapture(device.serial, device)
        bugs.beginRecording()
        repeat(8) { index ->
            mirror.frames.value = MirrorFrame(64, 128, IntArray(64 * 128) { -16_777_216 + index }, frameNumber = (index + 1).toLong())
            delay(90)
        }
        val sampleDeadline = System.currentTimeMillis() + 15_000
        while (bugs.status.value.videoFrameCount < 4 && System.currentTimeMillis() < sampleDeadline) delay(20)

        val report = bugs.saveRecording(device)
        assertNotNull(report.videoStartedAtMillis, "recording should have a video start timestamp")
        assertNotNull(report.videoEndedAtMillis, "recording should have a video end timestamp")
        assertTrue(bugs.bugVideoFrameCount(report.id) >= 2, "expected multiple frames to exercise trim math")
        return bugs to report
    }
}
