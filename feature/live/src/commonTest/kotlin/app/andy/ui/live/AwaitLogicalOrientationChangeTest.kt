package app.andy.ui.live

import app.andy.model.AndroidDevice
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AwaitLogicalOrientationChangeTest {
    @Test
    fun returnsWhenAspectFlipsFromPortraitToLandscape() = runBlocking {
        val devices = FakeDisplaySizeDeviceService(
            sizes = ArrayDeque(
                listOf(
                    1080 to 2400,
                    1080 to 2400,
                    2400 to 1080,
                ),
            ),
        )
        val next = awaitLogicalOrientationChange(
            devices = devices,
            serial = "emulator-5554",
            previous = 1080 to 2400,
            timeoutMillis = 2_000,
            pollMillis = 1,
        )
        assertNotNull(next)
        assertTrue(next!!.first > next.second)
        assertEquals(2400 to 1080, next)
    }

    @Test
    fun returnsLastSizeWhenAspectNeverFlips() = runBlocking {
        val devices = FakeDisplaySizeDeviceService(
            sizes = ArrayDeque(listOf(1080 to 2400, 1080 to 2400)),
        )
        val next = awaitLogicalOrientationChange(
            devices = devices,
            serial = "emulator-5554",
            previous = 1080 to 2400,
            timeoutMillis = 50,
            pollMillis = 10,
        )
        assertEquals(1080 to 2400, next)
    }

    private class FakeDisplaySizeDeviceService(
        private val sizes: ArrayDeque<Pair<Int, Int>>,
    ) : DeviceService {
        override suspend fun discoverSdk(): SdkDiscovery =
            SdkDiscovery(null, null, null, null, null, emptyList())
        override suspend fun listDevices(): List<AndroidDevice> = emptyList()
        override suspend fun shell(serial: String, command: List<String>): CommandResult {
            if (command == listOf("dumpsys", "window", "displays")) {
                val size = sizes.removeFirstOrNull() ?: (1080 to 2400)
                return CommandResult.success(
                    """
                    Display: mDisplayId=0 (organized)
                      cur=${size.first}x${size.second}
                    """.trimIndent(),
                )
            }
            return CommandResult.success("OK\n")
        }
        override suspend fun emu(serial: String, command: List<String>): CommandResult =
            CommandResult.success("OK\n")
        override suspend fun pair(host: String, port: Int, code: String): CommandResult =
            CommandResult.success("ok")
        override suspend fun connect(host: String, port: Int): CommandResult = CommandResult.success("ok")
        override suspend fun disconnect(serial: String): CommandResult = CommandResult.success("ok")
        override suspend fun listMdnsServices(): List<MdnsService> = emptyList()
        override suspend fun mdnsAvailable(): Boolean = false
        override suspend fun generatePairingQr(content: String): ByteArray? = null
    }
}
