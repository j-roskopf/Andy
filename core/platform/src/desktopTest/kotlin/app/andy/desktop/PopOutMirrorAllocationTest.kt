package app.andy.desktop

import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.service.IosTargetRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PopOutMirrorAllocationTest {
    @AfterTest
    fun tearDown() {
        IosTargetRegistry.update(emptyList())
    }

    @Test
    fun loneAndroidPopOutUsesPrimaryWhenItOwnsLiveSession() {
        val id = gpuPopOutTargetId(
            windows = listOf(PopOutMirrorWindow("emulator-5554", preferPrimaryMirror = true)),
            primaryMirrorSerial = "emulator-5554",
        )
        assertEquals("emulator-5554", id)
    }

    @Test
    fun loneAndroidPopOutFallsBackToCpuWhenLiveMovedElsewhere() {
        val id = gpuPopOutTargetId(
            windows = listOf(PopOutMirrorWindow("emulator-5554", preferPrimaryMirror = true)),
            primaryMirrorSerial = "ios-sim-iphone-17-pro",
        )
        assertNull(id)
    }

    @Test
    fun iosPopOutClaimsMetalWhenAndroidIsAlsoOpen() {
        val iosId = "ios-sim-iphone-17-pro"
        IosTargetRegistry.update(
            listOf(
                IosTarget(
                    udid = iosId,
                    displayName = "iPhone 17 Pro",
                    kind = IosTargetKind.Simulator,
                    state = IosTargetState.Booted,
                ),
            ),
        )
        val id = gpuPopOutTargetId(
            windows = listOf(
                PopOutMirrorWindow("emulator-5554", preferPrimaryMirror = true),
                PopOutMirrorWindow(iosId, preferPrimaryMirror = true),
            ),
            primaryMirrorSerial = "emulator-5554",
        )
        assertEquals(iosId, id)
    }

    @Test
    fun multipleAndroidPopOutsNeverShareMetal() {
        val id = gpuPopOutTargetId(
            windows = listOf(
                PopOutMirrorWindow("emulator-5554", preferPrimaryMirror = true),
                PopOutMirrorWindow("emulator-5556", preferPrimaryMirror = true),
            ),
            primaryMirrorSerial = "emulator-5554",
        )
        assertNull(id)
    }
}
