package app.andy.ui.live

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.service.MirrorBackend
import app.andy.service.MirrorBackendKind
import app.andy.service.MirrorFrame
import app.andy.service.MirrorRendererMode
import app.andy.service.MirrorSession
import app.andy.ui.controls.FoldableDisplayProfile
import app.andy.ui.controls.FoldablePosture
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveMirrorSourceSizeTest {
    private val foldProfile = FoldableDisplayProfile(
        outerWidth = 1080,
        outerHeight = 2364,
        innerWidth = 2076,
        innerHeight = 2152,
    )

    @Test
    fun streamSizePrefersSessionOverStaleFrameWhenAspectsMatch() {
        val device = device(screenSize = "1080x2364")
        val frame = MirrorFrame(486, 1080, IntArray(0), frameNumber = 12)
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 492,
            height = 1080,
            readyForPresentation = true,
        )

        assertEquals(MirrorSourceSize(492, 1080), liveMirrorStreamSize(device, frame, session))
    }

    @Test
    fun frameSizePrefersDecodedFrameOverSessionForOverlays() {
        val frame = MirrorFrame(486, 1080, IntArray(0), frameNumber = 12)
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 492,
            height = 1080,
            readyForPresentation = true,
        )

        assertEquals(MirrorSourceSize(486, 1080), liveMirrorFrameSize(frame, session))
    }

    @Test
    fun streamSizePrefersOpenSessionWhenUnfolding() {
        val device = device(screenSize = "1080x2364")
        val frame = MirrorFrame(486, 1080, IntArray(0), frameNumber = 12)
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 1040,
            height = 1078,
        )

        assertEquals(
            MirrorSourceSize(1040, 1078),
            liveMirrorStreamSize(
                device = device,
                frame = frame,
                session = session,
                foldableProfile = foldProfile,
                foldableHingeAngle = FoldablePosture.Opened.defaultAngle,
            ),
        )
    }

    @Test
    fun streamSizePrefersClosedFrameWhenFolding() {
        val device = device(screenSize = "2076x2152")
        val frame = MirrorFrame(452, 1080, IntArray(0), frameNumber = 12)
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 2076,
            height = 2152,
        )

        assertEquals(
            MirrorSourceSize(452, 1080),
            liveMirrorStreamSize(
                device = device,
                frame = frame,
                session = session,
                foldableProfile = foldProfile,
                foldableHingeAngle = FoldablePosture.Closed.defaultAngle,
            ),
        )
    }

    @Test
    fun layoutSizeUsesProfileAspectWhenStreamStillOpenButPostureClosed() {
        val device = device(screenSize = "2076x2152")
        val frame = MirrorFrame(1040, 1078, IntArray(0), frameNumber = 9)
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 1040,
            height = 1078,
        )

        assertEquals(
            MirrorSourceSize(1080, 2364),
            liveMirrorLayoutSize(
                device = device,
                frame = frame,
                session = session,
                captureHint = null,
                foldableProfile = foldProfile,
                foldableHingeAngle = FoldablePosture.Closed.defaultAngle,
            ),
        )
    }

    @Test
    fun streamSizeIgnoresPhysicalDeviceScreenSize() {
        val device = device(screenSize = "2076x2152")

        assertEquals(MirrorSourceSize(1080, 2400), liveMirrorStreamSize(device, frame = null, session = null))
    }

    @Test
    fun prefersLiveFrameOverDeviceScreenSize() {
        val device = device(screenSize = "1080x1920")
        val frame = MirrorFrame(486, 1080, IntArray(0), frameNumber = 3)

        assertEquals(MirrorSourceSize(486, 1080), liveMirrorSourceSize(device, frame))
    }

    @Test
    fun prefersSessionOverStaleDeviceScreenSizeWhenFrameMissing() {
        // After fold/unfold on GPU paths, CPU frames may be absent while session
        // already reflects the new capture size. device.screenSize often lags.
        val device = device(screenSize = "1080x2364")
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 2076,
            height = 2152,
        )

        assertEquals(MirrorSourceSize(2076, 2152), liveMirrorSourceSize(device, frame = null, session))
    }

    @Test
    fun usesDeviceScreenSizeWhenFrameIsMissingOrSentinel() {
        val device = device(screenSize = "1080x2400")

        assertEquals(MirrorSourceSize(1080, 2400), liveMirrorSourceSize(device, null))
        assertEquals(
            MirrorSourceSize(1080, 2400),
            liveMirrorSourceSize(device, MirrorFrame(1, 1, intArrayOf(0xff000000.toInt()))),
        )
    }

    @Test
    fun fallsBackToTallPhoneDefault() {
        assertEquals(MirrorSourceSize(1080, 2400), liveMirrorSourceSize(null, null))
    }

    @Test
    fun foldableCaptureHintOverridesStaleFrameAndSession() {
        // After Closed/Opened, keep the Live host on the expected geometry until the
        // restarted mirror session matches — otherwise open/close looks like a no-op.
        val device = device(screenSize = "2076x2152")
        val frame = MirrorFrame(2076, 2152, IntArray(0), frameNumber = 9)
        val session = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 2076,
            height = 2152,
        )
        val hint = MirrorSourceSize(1080, 2364)

        assertEquals(
            hint,
            liveMirrorSourceSize(
                device = device,
                frame = frame,
                session = session,
                captureHint = hint,
                foldableProfile = foldProfile,
                foldableHingeAngle = FoldablePosture.Closed.defaultAngle,
            ),
        )
    }

    @Test
    fun confirmedLogicalOrientationHintLeadsAStaleNonFoldableStream() {
        val device = device(screenSize = "1080x2400")
        val stalePortraitFrame = MirrorFrame(480, 1080, IntArray(0), frameNumber = 10)
        val stalePortraitSession = MirrorSession(
            serial = "emulator-5554",
            requestedMode = MirrorRendererMode.Auto,
            backend = MirrorBackend(MirrorBackendKind.NativeHardware),
            width = 480,
            height = 1080,
            readyForPresentation = true,
        )
        val confirmedLandscapeFrame = MirrorSourceSize(1080, 480)

        assertEquals(
            confirmedLandscapeFrame,
            liveMirrorSourceSize(
                device = device,
                frame = stalePortraitFrame,
                session = stalePortraitSession,
                captureHint = confirmedLandscapeFrame,
            ),
        )
        assertEquals(
            MirrorSourceSize(480, 1080),
            liveMirrorFrameSize(stalePortraitFrame, stalePortraitSession),
            "the mirror surface keeps the real buffer size so it can fit without stretching",
        )
    }

    private fun device(screenSize: String) = AndroidDevice(
        serial = "emulator-5554",
        displayName = "Pixel_8",
        kind = DeviceKind.Emulator,
        state = DeviceConnectionState.Online,
        transport = DeviceTransport.Unknown,
        screenSize = screenSize,
    )
}
