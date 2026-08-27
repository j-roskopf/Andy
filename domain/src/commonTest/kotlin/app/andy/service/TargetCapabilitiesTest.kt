package app.andy.service

import app.andy.AndyDestination
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetCapabilitiesTest {
    @Test
    fun androidEnablesFullSurface() {
        val caps = TargetCapabilities.Android
        assertTrue(caps.destinationAvailable(AndyDestination.Network))
        assertTrue(caps.destinationAvailable(AndyDestination.Snapshots))
        assertTrue(caps.destinationAvailable(AndyDestination.Inspector))
        assertTrue(caps.hardwareButtons)
        assertTrue(caps.androidIntentModes)
    }

    @Test
    fun simulatorExcludesAndroidOnlyDestinations() {
        val caps = TargetCapabilities.Simulator
        assertFalse(caps.destinationAvailable(AndyDestination.Network))
        assertFalse(caps.destinationAvailable(AndyDestination.Snapshots))
        assertFalse(caps.destinationAvailable(AndyDestination.Inspector))
        assertTrue(caps.destinationAvailable(AndyDestination.Apps))
        assertTrue(caps.destinationAvailable(AndyDestination.Design))
        assertTrue(caps.destinationAvailable(AndyDestination.Catalog))
        assertTrue(caps.destinationAvailable(AndyDestination.Controls))
        assertTrue(caps.destinationAvailable(AndyDestination.Logcat))
        assertFalse(caps.androidIntentModes)
        assertFalse(caps.hardwareButtons)
    }

    @Test
    fun physicalIsViewOnlyUntilDeveloperMode() {
        val caps = TargetCapabilities.Physical
        assertTrue(caps.destinationAvailable(AndyDestination.Live))
        assertTrue(caps.destinationAvailable(AndyDestination.Design))
        assertTrue(caps.destinationAvailable(AndyDestination.Recordings))
        assertTrue(caps.destinationAvailable(AndyDestination.Bugs))
        assertFalse(caps.destinationAvailable(AndyDestination.Apps))
        assertFalse(caps.destinationAvailable(AndyDestination.Controls))
        assertFalse(caps.input)
        assertTrue(caps.requiresDeveloperMode)
    }

    @Test
    fun ofSelectsByKind() {
        val sim = IosTarget("u1", "Sim", IosTargetKind.Simulator, IosTargetState.Booted)
        val phone = IosTarget("u2", "Phone", IosTargetKind.Physical, IosTargetState.Unknown, transport = IosTransport.Usb)
        assertFalse(TargetCapabilities.of(sim).requiresDeveloperMode)
        assertTrue(TargetCapabilities.of(phone).requiresDeveloperMode)
    }
}
