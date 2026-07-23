package app.andy.ui.live

import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorFrame
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.service.MirrorStats
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MirrorLoadingTest {
    @Test
    fun loadingWhileConnectingWithoutPresentation() {
        assertTrue(
            isMirrorSurfaceLoading(
                serial = "emulator-5554",
                frame = null,
                session = null,
                mirrorStatus = "Starting scrcpy…",
            ),
        )
    }

    @Test
    fun loadingWhenMetadataKnownButNothingPresentedYet() {
        assertTrue(
            isMirrorSurfaceLoading(
                serial = "emulator-5554",
                frame = MirrorFrame(1080, 2400, IntArray(0)),
                session = MirrorSession(
                    serial = "emulator-5554",
                    requestedMode = MirrorRendererMode.Accelerated,
                    backend = MirrorBackend(MirrorBackendKind.NativeHardware),
                    width = 1080,
                    height = 2400,
                ),
                mirrorStatus = "Connected",
            ),
        )
    }

    @Test
    fun notLoadingWhenGpuFramesPresented() {
        assertFalse(
            isMirrorSurfaceLoading(
                serial = "emulator-5554",
                frame = MirrorFrame(1080, 2400, IntArray(0), frameNumber = 12),
                session = MirrorSession(
                    serial = "emulator-5554",
                    requestedMode = MirrorRendererMode.Accelerated,
                    backend = MirrorBackend(MirrorBackendKind.NativeHardware),
                    stats = MirrorStats(displayedFps = 30f, framesPresented = 12),
                    width = 1080,
                    height = 2400,
                ),
                mirrorStatus = "Connected",
            ),
        )
    }

    @Test
    fun notLoadingWhenSessionFailed() {
        assertFalse(
            isMirrorSurfaceLoading(
                serial = "emulator-5554",
                frame = null,
                session = MirrorSession(
                    serial = "emulator-5554",
                    requestedMode = MirrorRendererMode.Accelerated,
                    backend = MirrorBackend(MirrorBackendKind.Unavailable),
                    failureReason = "Metal unavailable",
                ),
                mirrorStatus = "Accelerated mirror unavailable",
            ),
        )
    }

    @Test
    fun hasMirrorRenderedUsesSessionDimensionsAndStats() {
        assertTrue(
            hasMirrorRendered(
                frame = MirrorFrame(1, 1, intArrayOf(0)),
                session = MirrorSession(
                    serial = "device",
                    requestedMode = MirrorRendererMode.Accelerated,
                    backend = MirrorBackend(MirrorBackendKind.NativeHardware),
                    stats = MirrorStats(displayedFps = 24f, framesPresented = 8),
                    width = 1179,
                    height = 2556,
                ),
            ),
        )
    }
}
