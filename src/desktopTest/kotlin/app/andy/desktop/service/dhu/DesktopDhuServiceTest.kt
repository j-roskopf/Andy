package app.andy.desktop.service.dhu

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.dhu.capture.DhuWindowBounds
import app.andy.desktop.service.dhu.capture.DhuWindowHost
import app.andy.desktop.service.dhu.capture.DhuWindowRef
import app.andy.desktop.service.dhu.capture.UnsupportedDhuWindowHost
import app.andy.model.AndroidDevice
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.DeviceTransport
import app.andy.model.MdnsService
import app.andy.model.SdkDiscovery
import app.andy.service.CommandResult
import app.andy.service.DeviceService
import app.andy.service.DhuCaptureFrame
import app.andy.service.DhuCommandFactory
import app.andy.service.DhuHostKind
import app.andy.service.DhuSessionPhase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDhuServiceTest {
    @Test
    fun interpretTransportDisconnectAsHeadUnitServerGuidance() {
        val msg = DesktopDhuService.interpretDhuExit(
            listOf(
                "Connecting over ADB to localhost:19001...connected.",
                "Failed to read from transport - disconnect. Exiting...",
            ),
            fallback = "gone",
        )
        assertTrue(msg.contains("Start head unit server", ignoreCase = true))
        assertTrue(msg.contains("failed to read from transport", ignoreCase = true))
    }

    @Test
    fun interpretFramingErrorAsUsbHandshakeGuidance() {
        val msg = DesktopDhuService.interpretDhuExit(
            listOf(
                "Attached!",
                "Sending Framing Error notification!",
                "[E]: Unrecoverable error -251",
                "[E]: Out of sync with phone. Exiting...",
            ),
            fallback = "gone",
        )
        assertTrue(msg.contains("-251") || msg.contains("framing", ignoreCase = true))
        assertTrue(msg.contains("unplug", ignoreCase = true) || msg.contains("Retry", ignoreCase = true))
        assertTrue(DesktopDhuService.isLinkBroken(listOf("Framing Error notification")))
        assertTrue(
            DesktopDhuService.isLinkReady(
                listOf("Phone reported protocol version 1.7", "SSL negotiation finished successfully 1"),
            ),
        )
        assertFalse(
            DesktopDhuService.isLinkReady(
                listOf("SSL negotiation finished successfully 1", "Framing Error notification"),
            ),
        )
    }

    @Test
    fun startFailsWhenReadinessBlockedAndDoesNotLeaveSessionRunning() = runBlocking {
        val devices = FakeDevices(online = false)
        val service = DesktopDhuService(
            devices = devices,
            runner = CommandRunner { _, _ -> CommandResult.success() },
            host = UnsupportedDhuWindowHost(DhuHostKind.MacOs),
            configDir = createTempDirectory(prefix = "andy-dhu-svc-").toFile(),
        )
        val result = service.start("serial-1")
        assertTrue(!result.isSuccess)
        assertEquals(DhuSessionPhase.Failed, service.session.value?.phase)
    }

    @Test
    fun startFailsWhenHeadUnitServerNotListening() = runBlocking {
        val sdk = createTempDirectory(prefix = "andy-sdk-hus-").toFile()
        val autoDir = File(sdk, "extras/google/auto").also { it.mkdirs() }
        File(autoDir, "desktop-head-unit").also {
            it.writeText("#!/bin/sh\nexit 0\n")
            it.setExecutable(true)
        }
        File(autoDir, "libusb-1.0.dylib").writeText("stub")
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("shell") && command.any { it.contains("/proc/net/tcp") } -> {
                    CommandResult.success(
                        "0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 0 1",
                    )
                }
                else -> CommandResult.success()
            }
        }
        val host = object : DhuWindowHost by UnsupportedDhuWindowHost(DhuHostKind.MacOs) {
            override fun environment() = DhuHostEnvironment(
                hostKind = DhuHostKind.MacOs,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "ok",
            )
        }
        val service = DesktopDhuService(
            devices = FakeDevices(
                online = true,
                kind = DeviceKind.Emulator,
                transport = DeviceTransport.Unknown,
                sdk = SdkDiscovery(
                    sdkPath = sdk.absolutePath,
                    adbPath = "/usr/bin/true",
                    emulatorPath = null,
                    sdkManagerPath = null,
                    avdManagerPath = null,
                ),
            ),
            runner = runner,
            host = host,
            configDir = createTempDirectory(prefix = "andy-dhu-hus-").toFile(),
        )
        try {
            val result = service.start("serial-1")
            assertTrue(!result.isSuccess)
            val detail = (result.stderr + result.stdout + (service.session.value?.message ?: ""))
            assertTrue(
                detail.contains("Head Unit Server", ignoreCase = true) ||
                    service.readiness.value.blocking.any { it.id == "head_unit_server" },
            )
            assertEquals(DhuSessionPhase.Failed, service.session.value?.phase)
        } finally {
            runCatching { service.stop() }
            sdk.deleteRecursively()
        }
    }

    @Test
    fun singleSessionReplacementRemovesPreviousForward() = runBlocking {
        val sdk = createTempDirectory(prefix = "andy-sdk-").toFile()
        val autoDir = File(sdk, "extras/google/auto").also { it.mkdirs() }
        File(autoDir, "desktop-head-unit").also {
            it.writeText(
                """
                #!/bin/sh
                echo "SSL negotiation finished successfully 1"
                echo "Verify returned: ok"
                while true; do sleep 60; done
                """.trimIndent(),
            )
            it.setExecutable(true)
        }
        File(autoDir, "libusb-1.0.dylib").writeText("stub")

        val removed = mutableListOf<Int>()
        val forwarded = mutableListOf<String>()
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("shell") && command.any { it.contains("/proc/net/tcp") } -> {
                    CommandResult.success(
                        // 127.0.0.1:5277 LISTEN (0A)
                        "0: 0100007F:149D 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 0 1",
                    )
                }
                command.contains("dumpsys") && command.contains("usb") -> {
                    CommandResult.success("current_functions=ADB\n")
                }
                command.contains("forward") && command.contains("--remove") -> {
                    val port = command.last().removePrefix("tcp:").toInt()
                    removed += port
                    CommandResult.success()
                }
                command.contains("forward") -> {
                    forwarded += command.joinToString(" ")
                    CommandResult.success()
                }
                else -> CommandResult.success()
            }
        }
        val host = object : DhuWindowHost by UnsupportedDhuWindowHost(DhuHostKind.MacOs) {
            override fun environment() = DhuHostEnvironment(
                hostKind = DhuHostKind.MacOs,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "ok",
            )
            override fun findWindow(processPid: Long?, titleHint: String) =
                DhuWindowRef(id = "1", pid = processPid, title = titleHint)
            override fun hideFromUser(window: DhuWindowRef) = true
            override fun capture(window: DhuWindowRef) =
                DhuCaptureFrame(2, 2, intArrayOf(0, 0, 0, 0), 1)
            override fun bounds(window: DhuWindowRef) = DhuWindowBounds(0, 0, 2, 2)
            override fun resize(window: DhuWindowRef, width: Int, height: Int) = true
        }
        val devices = FakeDevices(
            online = true,
            kind = DeviceKind.Emulator,
            transport = DeviceTransport.Unknown,
            sdk = SdkDiscovery(
                sdkPath = sdk.absolutePath,
                adbPath = "/usr/bin/true",
                emulatorPath = null,
                sdkManagerPath = null,
                avdManagerPath = null,
            ),
        )
        val service = DesktopDhuService(
            devices = devices,
            runner = runner,
            host = host,
            configDir = createTempDirectory(prefix = "andy-dhu-cfg-").toFile(),
        )
        try {
            service.start("serial-1")
            assertNotNull(service.session.value)
            val firstPort = service.session.value!!.localPort
            service.start("serial-1")
            assertTrue(removed.contains(firstPort) || forwarded.size >= 2)
            service.stop()
            assertNull(service.session.value)
            assertTrue(removed.isNotEmpty())
        } finally {
            runCatching { service.stop() }
            sdk.deleteRecursively()
        }
    }

    @Test
    fun usbPhysicalDeviceSkipsAdbForwardAndHeadUnitServer() = runBlocking {
        val sdk = createTempDirectory(prefix = "andy-sdk-usb-").toFile()
        val autoDir = File(sdk, "extras/google/auto").also { it.mkdirs() }
        File(autoDir, "desktop-head-unit").also {
            it.writeText(
                """
                #!/bin/sh
                echo "SSL negotiation finished successfully 1"
                echo "Verify returned: ok"
                while true; do sleep 60; done
                """.trimIndent(),
            )
            it.setExecutable(true)
        }
        File(autoDir, "libusb-1.0.dylib").writeText("stub")
        val forwarded = mutableListOf<String>()
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("dumpsys") && command.contains("usb") -> {
                    CommandResult.success("current_functions=ADB\n")
                }
                command.contains("forward") -> {
                    forwarded += command.joinToString(" ")
                    CommandResult.success()
                }
                command.contains("shell") && command.any { it.contains("/proc/net/tcp") } -> {
                    error("USB path must not probe Head Unit Server")
                }
                else -> CommandResult.success()
            }
        }
        val host = object : DhuWindowHost by UnsupportedDhuWindowHost(DhuHostKind.MacOs) {
            override fun environment() = DhuHostEnvironment(
                hostKind = DhuHostKind.MacOs,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "ok",
            )
            override fun findWindow(processPid: Long?, titleHint: String) =
                DhuWindowRef(id = "1", pid = processPid, title = titleHint)
            override fun hideFromUser(window: DhuWindowRef) = true
            override fun capture(window: DhuWindowRef) =
                DhuCaptureFrame(2, 2, intArrayOf(0, 0, 0, 0), 1)
            override fun bounds(window: DhuWindowRef) = DhuWindowBounds(0, 0, 2, 2)
            override fun resize(window: DhuWindowRef, width: Int, height: Int) = true
        }
        val service = DesktopDhuService(
            devices = FakeDevices(
                online = true,
                kind = DeviceKind.Physical,
                transport = DeviceTransport.Usb,
                sdk = SdkDiscovery(
                    sdkPath = sdk.absolutePath,
                    adbPath = "/usr/bin/true",
                    emulatorPath = null,
                    sdkManagerPath = null,
                    avdManagerPath = null,
                ),
            ),
            runner = runner,
            host = host,
            configDir = createTempDirectory(prefix = "andy-dhu-usb-").toFile(),
        )
        try {
            val result = service.start("serial-1")
            assertTrue(result.isSuccess, result.stderr)
            assertTrue(forwarded.isEmpty())
            assertEquals(0, service.session.value?.localPort)
            assertTrue(service.readiness.value.ready)
            assertTrue(service.readiness.value.checks.none { it.id == "head_unit_server" })
            assertTrue(
                service.console.value.lines.any { it.contains("--usb=serial-1") },
            )
            service.stop()
        } finally {
            runCatching { service.stop() }
            sdk.deleteRecursively()
        }
    }

    @Test
    fun consoleSendFailsWithoutLiveProcess() = runBlocking {
        val service = DesktopDhuService(
            devices = FakeDevices(online = false),
            runner = CommandRunner { _, _ -> CommandResult.success() },
            host = UnsupportedDhuWindowHost(DhuHostKind.MacOs),
            configDir = createTempDirectory(prefix = "andy-dhu-console-").toFile(),
        )
        val result = service.sendConsoleCommand("day")
        assertTrue(!result.isSuccess)
    }

    @Test
    fun spontaneousProcessExitRemovesAdbForward() = runBlocking {
        val sdk = createTempDirectory(prefix = "andy-sdk-exit-").toFile()
        val autoDir = File(sdk, "extras/google/auto").also { it.mkdirs() }
        File(autoDir, "desktop-head-unit").also {
            it.writeText(
                """
                #!/bin/sh
                echo "SSL negotiation finished successfully 1"
                echo "Verify returned: ok"
                sleep 1
                exit 1
                """.trimIndent(),
            )
            it.setExecutable(true)
        }
        File(autoDir, "libusb-1.0.dylib").writeText("stub")
        val removed = mutableListOf<Int>()
        val runner = CommandRunner { command, _ ->
            when {
                command.contains("shell") && command.any { it.contains("/proc/net/tcp") } -> {
                    CommandResult.success(
                        "0: 0100007F:149D 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 0 1",
                    )
                }
                command.contains("forward") && command.contains("--remove") -> {
                    removed += command.last().removePrefix("tcp:").toInt()
                    CommandResult.success()
                }
                command.contains("forward") -> CommandResult.success()
                else -> CommandResult.success()
            }
        }
        val host = object : DhuWindowHost by UnsupportedDhuWindowHost(DhuHostKind.MacOs) {
            override fun environment() = DhuHostEnvironment(
                hostKind = DhuHostKind.MacOs,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "ok",
            )
            override fun findWindow(processPid: Long?, titleHint: String) =
                DhuWindowRef(id = "1", pid = processPid, title = titleHint)
            override fun hideFromUser(window: DhuWindowRef) = true
            override fun capture(window: DhuWindowRef) =
                DhuCaptureFrame(2, 2, intArrayOf(0, 0, 0, 0), 1)
            override fun bounds(window: DhuWindowRef) = DhuWindowBounds(0, 0, 2, 2)
            override fun resize(window: DhuWindowRef, width: Int, height: Int) = true
        }
        val service = DesktopDhuService(
            devices = FakeDevices(
                online = true,
                kind = DeviceKind.Emulator,
                transport = DeviceTransport.Unknown,
                sdk = SdkDiscovery(
                    sdkPath = sdk.absolutePath,
                    adbPath = "/usr/bin/true",
                    emulatorPath = null,
                    sdkManagerPath = null,
                    avdManagerPath = null,
                ),
            ),
            runner = runner,
            host = host,
            configDir = createTempDirectory(prefix = "andy-dhu-exit-").toFile(),
        )
        try {
            val result = service.start("serial-1")
            assertTrue(result.isSuccess, result.stderr)
            val port = service.session.value!!.localPort
            assertTrue(port > 0)
            // Wait for watchProcess teardown after the script exits.
            val deadline = System.nanoTime() + 8_000_000_000L
            while (System.nanoTime() < deadline && service.session.value?.phase != DhuSessionPhase.Failed) {
                kotlinx.coroutines.delay(100)
            }
            assertEquals(DhuSessionPhase.Failed, service.session.value?.phase)
            assertTrue(removed.contains(port), "expected forward tcp:$port removed, got $removed")
            val open = service.openExternalTroubleshooting()
            assertTrue(!open.isSuccess)
            assertTrue(open.stderr.contains("Retry", ignoreCase = true) || open.stderr.contains("managed", ignoreCase = true))
        } finally {
            runCatching { service.stop() }
            sdk.deleteRecursively()
        }
    }

    @Test
    fun commandFactoryPortCleanupMatchesForward() {
        val remove = DhuCommandFactory.buildAdbForwardRemove("/adb", "s", 9)
        assertEquals(listOf("/adb", "-s", "s", "forward", "--remove", "tcp:9"), remove)
    }

    private class FakeDevices(
        private val online: Boolean,
        private val kind: DeviceKind = DeviceKind.Physical,
        private val transport: DeviceTransport = DeviceTransport.Usb,
        private val sdk: SdkDiscovery = SdkDiscovery(null, null, null, null, null),
    ) : DeviceService {
        override suspend fun discoverSdk() = sdk
        override suspend fun listDevices() = listOf(
            AndroidDevice(
                serial = "serial-1",
                displayName = "Phone",
                kind = kind,
                state = if (online) DeviceConnectionState.Online else DeviceConnectionState.Offline,
                transport = transport,
            ),
        )
        override suspend fun shell(serial: String, command: List<String>) = CommandResult.success()
        override suspend fun pair(host: String, port: Int, code: String) = CommandResult.success()
        override suspend fun connect(host: String, port: Int) = CommandResult.success()
        override suspend fun disconnect(serial: String) = CommandResult.success()
        override suspend fun listMdnsServices() = emptyList<MdnsService>()
        override suspend fun mdnsAvailable() = false
        override suspend fun generatePairingQr(content: String) = null
    }
}
