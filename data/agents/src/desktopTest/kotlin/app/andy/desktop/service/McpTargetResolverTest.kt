package app.andy.desktop.service

import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind
import app.andy.model.IosTargetState
import app.andy.model.IosTransport
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.model.WorkspaceState
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.IosDeviceService
import app.andy.service.IosTargetRegistry
import app.andy.service.UnavailableIosDeviceService
import app.andy.service.WorkspaceStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpTargetResolverTest {
    @BeforeTest
    fun setUp() {
        IosTargetRegistry.update(emptyList())
    }

    @AfterTest
    fun tearDown() {
        IosTargetRegistry.update(emptyList())
    }

    @Test
    fun resolvesExplicitIosUdidAndRefreshesRegistry() = runBlocking {
        val ios = FakeIosDevices(listOf(bootedSim("SIM-1")))
        val resolver = McpTargetResolver(FakeDevices(emptyList()), ios, FakeWorkspace())
        assertEquals("SIM-1", resolver.resolve("SIM-1"))
        assertTrue(IosTargetRegistry.isIosTarget("SIM-1"))
    }

    @Test
    fun autoPicksSingleBootedSimulator() = runBlocking {
        val ios = FakeIosDevices(listOf(bootedSim("SIM-1")))
        val resolver = McpTargetResolver(FakeDevices(emptyList()), ios, FakeWorkspace())
        assertEquals("SIM-1", resolver.resolve(null))
    }

    @Test
    fun resolveAndroidRejectsIosTarget() = runBlocking {
        val ios = FakeIosDevices(listOf(bootedSim("SIM-1")))
        val resolver = McpTargetResolver(FakeDevices(emptyList()), ios, FakeWorkspace())
        val error = assertFailsWith<IllegalArgumentException> { resolver.resolveAndroid("SIM-1") }
        assertTrue(error.message!!.contains("Android-only"))
    }

    @Test
    fun physicalIsReachableAndFlagged() = runBlocking {
        val ios = FakeIosDevices(
            listOf(
                IosTarget(
                    udid = "PHYS-1",
                    displayName = "iPhone",
                    kind = IosTargetKind.Physical,
                    state = IosTargetState.Unknown,
                    transport = IosTransport.Usb,
                ),
            ),
        )
        val resolver = McpTargetResolver(FakeDevices(emptyList()), ios, FakeWorkspace())
        assertEquals("PHYS-1", resolver.resolve("PHYS-1"))
        assertTrue(resolver.isPhysicalIos("PHYS-1"))
    }

    @Test
    fun prefersWorkspaceSelectionWhenStillOnline() = runBlocking {
        val android = FakeDevices(
            listOf(
                AndroidDevice("emu-1", "Pixel", DeviceKind.Emulator, DeviceConnectionState.Online),
                AndroidDevice("emu-2", "Tablet", DeviceKind.Emulator, DeviceConnectionState.Online),
            ),
        )
        val resolver = McpTargetResolver(android, FakeIosDevices(emptyList()), FakeWorkspace("emu-2"))
        assertEquals("emu-2", resolver.resolve(null))
    }

    @Test
    fun iosMcpToolNamesAreRegisteredInCatalog() {
        assertTrue(IosMcpToolNames.contains("ios_boot"))
        assertTrue(IosMcpToolNames.contains("ios_push"))
        assertEquals(18, IosMcpToolNames.size)
    }

    private fun bootedSim(udid: String) = IosTarget(
        udid = udid,
        displayName = "iPhone",
        kind = IosTargetKind.Simulator,
        state = IosTargetState.Booted,
    )
}

private class FakeDevices(private val devices: List<AndroidDevice>) : DeviceService {
    override suspend fun discoverSdk() = SdkDiscovery(null, null, null, null, null)
    override suspend fun listDevices(): List<AndroidDevice> = devices
    override suspend fun shell(serial: String, command: List<String>) = CommandResult.failure("unused")
    override suspend fun pair(host: String, port: Int, code: String) = CommandResult.failure("unused")
    override suspend fun connect(host: String, port: Int) = CommandResult.failure("unused")
    override suspend fun disconnect(serial: String) = CommandResult.failure("unused")
    override suspend fun listMdnsServices(): List<MdnsService> = emptyList()
    override suspend fun mdnsAvailable() = false
    override suspend fun generatePairingQr(content: String): ByteArray? = null
}

private class FakeIosDevices(private val targets: List<IosTarget>) : IosDeviceService by UnavailableIosDeviceService {
    override suspend fun listTargets(): List<IosTarget> = targets
}

private class FakeWorkspace(private val selected: String? = null) : WorkspaceStore {
    override suspend fun load(): WorkspaceState = WorkspaceState(selectedDeviceSerial = selected)
    override suspend fun save(state: WorkspaceState) = Unit
}
