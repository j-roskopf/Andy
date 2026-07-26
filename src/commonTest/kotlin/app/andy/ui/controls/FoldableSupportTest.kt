package app.andy.ui.controls

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.model.VirtualDevice
import app.andy.model.VirtualDeviceType
import app.andy.service.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldableSupportTest {
    @Test
    fun postureIsBinaryOpenOrClosed() {
        assertEquals(FoldablePosture.Closed, foldablePostureForAngle(0f))
        assertEquals(FoldablePosture.Closed, foldablePostureForAngle(89f))
        assertEquals(FoldablePosture.Opened, foldablePostureForAngle(90f))
        assertEquals(FoldablePosture.Opened, foldablePostureForAngle(180f))
    }

    @Test
    fun detectsFoldableFromModelName() {
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Pixel_Fold",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Unknown,
            model = "sdk_gphone64_arm64",
        )
        assertTrue(isFoldableEmulator(device))
    }

    @Test
    fun detectsFoldableFromAvdCatalog() {
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "7.6_Foldable",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Unknown,
            model = "sdk_gphone64_arm64",
        )
        assertTrue(isFoldableEmulator(device))

        val plain = device.copy(displayName = "Pixel_8_API_36", model = "Pixel_8")
        assertFalse(isFoldableEmulator(plain))
        assertTrue(
            isFoldableEmulator(
                plain.copy(displayName = "Custom_Device"),
                listOf(VirtualDevice("Custom_Device", null, null, null, true, 36, VirtualDeviceType.Foldable)),
            ),
        )
    }

    @Test
    fun catalogOverridesNameHeuristicsForNonFoldableAvds() {
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Pixel_Fold_Testbed",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Unknown,
            model = "sdk_gphone64_arm64",
        )
        assertTrue(isFoldableEmulator(device))
        assertFalse(
            isFoldableEmulator(
                device,
                listOf(VirtualDevice("Pixel_Fold_Testbed", null, null, null, true, 36, VirtualDeviceType.Phone)),
            ),
        )
    }

    @Test
    fun detectsFoldableFromAvdDisplayProfileConfig() {
        val device = AndroidDevice(
            serial = "emulator-5554",
            displayName = "Custom_Device",
            kind = DeviceKind.Emulator,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Unknown,
            model = "sdk_gphone64_arm64",
        )
        assertTrue(
            isFoldableEmulator(
                device,
                listOf(
                    VirtualDevice(
                        name = "Custom_Device",
                        path = null,
                        target = null,
                        abi = null,
                        running = true,
                        apiLevel = 36,
                        deviceType = VirtualDeviceType.Unknown,
                        config = mapOf(
                            "hw.lcd.width" to "2076",
                            "hw.lcd.height" to "2152",
                            "hw.displayRegion.0.1.width" to "1080",
                            "hw.displayRegion.0.1.height" to "2364",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun physicalFoldablesDoNotExposeEmulatorControls() {
        val device = AndroidDevice(
            serial = "ABC123",
            displayName = "Pixel Fold",
            kind = DeviceKind.Physical,
            state = DeviceConnectionState.Online,
            transport = DeviceTransport.Usb,
            model = "Pixel_Fold",
        )
        assertFalse(isFoldableEmulator(device))
    }

    @Test
    fun formatsHingeAngle() {
        assertEquals("90°", formatHingeAngle(90f))
        assertEquals("180°", formatHingeAngle(179.6f))
    }

    @Test
    fun parsesAvdDisplayProfileAndMorphsAspect() {
        val profile = foldableDisplayProfile(
            mapOf(
                "hw.lcd.width" to "2076",
                "hw.lcd.height" to "2152",
                "hw.displayRegion.0.1.width" to "1080",
                "hw.displayRegion.0.1.height" to "2364",
            ),
        )
        requireNotNull(profile)
        assertEquals(profile.outerAspect, profile.aspectForOpenAmount(0f), 0.0001f)
        assertEquals(profile.innerAspect, profile.aspectForOpenAmount(1f), 0.0001f)
        val mid = profile.aspectForOpenAmount(0.5f)
        assertTrue(mid > profile.outerAspect && mid < profile.innerAspect)
    }

    @Test
    fun emulatorConsoleKoIsFailureEvenWithExitZero() {
        assertFalse(CommandResult(0, "KO: bad sub-command\n", "").emulatorConsoleOk())
        assertTrue(CommandResult(0, "OK\n", "").emulatorConsoleOk())
    }

    @Test
    fun classifiesOuterVersusInnerDisplay() {
        assertTrue(isOuterFoldableDisplay(1080, 2364))
        assertFalse(isOuterFoldableDisplay(2076, 2152))
        assertEquals(1080 to 2364, parseWmSizePx("Physical size: 1080x2364\n"))
        assertEquals(
            1080 to 2364,
            parseWmSizePx("Hinge 0° · folded · 1080×2364"),
        )
    }
}
