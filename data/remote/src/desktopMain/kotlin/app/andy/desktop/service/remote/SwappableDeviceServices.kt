package app.andy.desktop.service.remote

import app.andy.model.AndroidDevice
import app.andy.model.AvdCreationConfig
import app.andy.model.AvdProfile
import app.andy.model.EmulatorSnapshot
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.model.SystemImage
import app.andy.model.VirtualDevice
import app.andy.service.AvdService
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.util.concurrent.atomic.AtomicReference

class SwappableDeviceService(
    initial: DeviceService,
) : DeviceService {
    private val active = AtomicReference(initial)
    private val activeFlow = MutableStateFlow(initial)

    fun switchTo(next: DeviceService) {
        active.set(next)
        activeFlow.value = next
    }

    private fun svc(): DeviceService = active.get()

    override suspend fun discoverSdk(): SdkDiscovery = svc().discoverSdk()
    override suspend fun listDevices(): List<AndroidDevice> = svc().listDevices()
    override fun observeDevicePresence(): Flow<Unit> =
        activeFlow.flatMapLatest { it.observeDevicePresence() }
    override suspend fun shell(serial: String, command: List<String>): CommandResult =
        svc().shell(serial, command)
    override suspend fun emu(serial: String, command: List<String>): CommandResult =
        svc().emu(serial, command)
    override suspend fun applyEmulatorDisplayRotation(serial: String, quarterTurn: Int): CommandResult =
        svc().applyEmulatorDisplayRotation(serial, quarterTurn)
    override suspend fun readEmulatorDisplayRotation(serial: String): Int? =
        svc().readEmulatorDisplayRotation(serial)
    override suspend fun pair(host: String, port: Int, code: String): CommandResult =
        svc().pair(host, port, code)
    override suspend fun connect(host: String, port: Int): CommandResult = svc().connect(host, port)
    override suspend fun disconnect(serial: String): CommandResult = svc().disconnect(serial)
    override suspend fun listMdnsServices(): List<MdnsService> = svc().listMdnsServices()
    override suspend fun mdnsAvailable(): Boolean = svc().mdnsAvailable()
    override suspend fun generatePairingQr(content: String): ByteArray? = svc().generatePairingQr(content)
}

class SwappableAvdService(
    initial: AvdService,
) : AvdService {
    private val active = AtomicReference(initial)

    fun switchTo(next: AvdService) {
        active.set(next)
    }

    private fun svc(): AvdService = active.get()

    override suspend fun listSystemImages(): List<SystemImage> = svc().listSystemImages()
    override suspend fun listProfiles(): List<AvdProfile> = svc().listProfiles()
    override suspend fun listVirtualDevices(): List<VirtualDevice> = svc().listVirtualDevices()
    override suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String): CommandResult =
        svc().createVirtualDevice(name, profileId, systemImagePackage)
    override suspend fun createVirtualDevice(config: AvdCreationConfig): CommandResult =
        svc().createVirtualDevice(config)
    override suspend fun startVirtualDevice(name: String): CommandResult = svc().startVirtualDevice(name)
    override suspend fun coldBootVirtualDevice(name: String): CommandResult = svc().coldBootVirtualDevice(name)
    override suspend fun stopVirtualDevice(name: String): CommandResult = svc().stopVirtualDevice(name)
    override suspend fun wipeVirtualDevice(name: String): CommandResult = svc().wipeVirtualDevice(name)
    override suspend fun deleteVirtualDevice(name: String): CommandResult = svc().deleteVirtualDevice(name)
    override suspend fun cloneVirtualDevice(sourceName: String, newName: String): CommandResult =
        svc().cloneVirtualDevice(sourceName, newName)
    override suspend fun installSystemImage(packageId: String): CommandResult = svc().installSystemImage(packageId)
    override suspend fun uninstallSystemImage(packageId: String): CommandResult = svc().uninstallSystemImage(packageId)
    override suspend fun listSnapshots(avdName: String): List<EmulatorSnapshot> = svc().listSnapshots(avdName)
    override suspend fun saveSnapshot(avdName: String, snapshotName: String): CommandResult =
        svc().saveSnapshot(avdName, snapshotName)
    override suspend fun restoreSnapshot(avdName: String, snapshotName: String): CommandResult =
        svc().restoreSnapshot(avdName, snapshotName)
    override suspend fun deleteSnapshot(avdName: String, snapshotName: String): CommandResult =
        svc().deleteSnapshot(avdName, snapshotName)
    override suspend fun renameSnapshot(avdName: String, oldName: String, newName: String): CommandResult =
        svc().renameSnapshot(avdName, oldName, newName)
}
