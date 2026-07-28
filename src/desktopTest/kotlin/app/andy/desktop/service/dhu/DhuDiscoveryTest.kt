package app.andy.desktop.service.dhu

import app.andy.service.DhuCheckStatus
import app.andy.service.DhuHostKind
import app.andy.service.DhuLinkTransport
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DhuDiscoveryTest {
    private class MapFs(
        private val dirs: Set<String> = emptySet(),
        private val files: Map<String, Boolean> = emptyMap(),
        private val listings: Map<String, List<String>> = emptyMap(),
    ) : DhuFs {
        override fun isDirectory(path: String) = path in dirs
        override fun isExecutable(path: String) = files[path] == true
        override fun isFile(path: String) = path in files
        override fun listNames(dir: String) = listings[dir].orEmpty()
    }

    @Test
    fun findsExecutableAndLibusbUnderExtrasGoogleAuto() {
        val auto = "/Android/sdk/extras/google/auto"
        val exe = "$auto/desktop-head-unit"
        val lib = "$auto/libusb-1.0.dylib"
        val fs = MapFs(
            dirs = setOf(auto),
            files = mapOf(exe to true, lib to true),
            listings = mapOf(auto to listOf("desktop-head-unit", "libusb-1.0.dylib")),
        )
        assertEquals(exe, DhuDiscovery.findExecutable(auto, isWindows = false, fs))
        assertEquals(lib, DhuDiscovery.findLibusb(auto, isWindows = false, DhuHostKind.MacOs, fs))
    }

    @Test
    fun findsLinuxStyleLibusbSoOnMacOsSdkPackage() {
        val auto = "/Android/sdk/extras/google/auto"
        val lib = "$auto/libusb-1.0.so"
        val fs = MapFs(
            dirs = setOf(auto),
            files = mapOf(lib to true),
            listings = mapOf(auto to listOf("desktop-head-unit", "libusb-1.0.so", "package.xml")),
        )
        assertEquals(lib, DhuDiscovery.findLibusb(auto, isWindows = false, DhuHostKind.MacOs, fs))
        assertTrue(DhuDiscovery.isLibusbFileName("libusb-1.0.so"))
        assertTrue(DhuDiscovery.isLibusbFileName("libusb-1.0.so.0"))
    }

    @Test
    fun evaluateAllowsWaylandHostForSeparateDhuWindow() {
        val auto = "/Android/sdk/extras/google/auto"
        val exe = "$auto/desktop-head-unit"
        val lib = "$auto/libusb-1.0.so"
        val readiness = DhuDiscovery.evaluate(
            sdkPath = "/Android/sdk",
            adbPath = "/Android/sdk/platform-tools/adb",
            serial = "ABC",
            deviceOnline = true,
            env = DhuHostEnvironment(
                hostKind = DhuHostKind.LinuxWayland,
                isWindows = false,
                capturePermissionGranted = false,
                capturePermissionDetail = "unused",
            ),
            fs = MapFs(
                dirs = setOf(auto),
                files = mapOf(exe to true, lib to true),
                listings = mapOf(auto to listOf("desktop-head-unit", "libusb-1.0.so")),
            ),
            headUnitServerListening = true,
            linkTransport = DhuLinkTransport.Adb,
        )
        assertTrue(readiness.ready)
        assertEquals(DhuCheckStatus.Ok, readiness.checks.first { it.id == "host" }.status)
        assertEquals(DhuCheckStatus.Ok, readiness.checks.first { it.id == "capture_permission" }.status)
    }

    @Test
    fun evaluateRequiresOnlineDeviceAdbSdkAutoAndPermissions() {
        val auto = "/sdk/extras/google/auto"
        val exe = "$auto/desktop-head-unit"
        val lib = "$auto/libusb-1.0.so"
        val fs = MapFs(
            dirs = setOf(auto),
            files = mapOf(exe to true, lib to true),
            listings = mapOf(auto to listOf("desktop-head-unit", "libusb-1.0.so")),
        )
        val ready = DhuDiscovery.evaluate(
            sdkPath = "/sdk",
            adbPath = "/sdk/platform-tools/adb",
            serial = "emu-1",
            deviceOnline = true,
            env = DhuHostEnvironment(
                hostKind = DhuHostKind.LinuxX11,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "X11 ok",
            ),
            fs = fs,
            headUnitServerListening = true,
            linkTransport = DhuLinkTransport.Adb,
        )
        assertTrue(ready.ready)
        assertEquals(exe, ready.executablePath)

        val noHus = DhuDiscovery.evaluate(
            sdkPath = "/sdk",
            adbPath = "/sdk/platform-tools/adb",
            serial = "emu-1",
            deviceOnline = true,
            env = DhuHostEnvironment(
                hostKind = DhuHostKind.LinuxX11,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "X11 ok",
            ),
            fs = fs,
            headUnitServerListening = false,
            linkTransport = DhuLinkTransport.Adb,
        )
        assertFalse(noHus.ready)
        assertEquals(DhuCheckStatus.Missing, noHus.checks.first { it.id == "head_unit_server" }.status)

        val usbReady = DhuDiscovery.evaluate(
            sdkPath = "/sdk",
            adbPath = "/sdk/platform-tools/adb",
            serial = "PHONE",
            deviceOnline = true,
            env = DhuHostEnvironment(
                hostKind = DhuHostKind.LinuxX11,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "X11 ok",
            ),
            fs = fs,
            headUnitServerListening = false,
            linkTransport = DhuLinkTransport.Usb,
        )
        assertTrue(usbReady.ready)
        assertTrue(usbReady.checks.none { it.id == "head_unit_server" })
        assertEquals(DhuCheckStatus.Ok, usbReady.checks.first { it.id == "link" }.status)

        val offline = DhuDiscovery.evaluate(
            sdkPath = "/sdk",
            adbPath = "/sdk/platform-tools/adb",
            serial = "emu-1",
            deviceOnline = false,
            env = DhuHostEnvironment(
                hostKind = DhuHostKind.LinuxX11,
                isWindows = false,
                capturePermissionGranted = true,
                capturePermissionDetail = "X11 ok",
            ),
            fs = fs,
        )
        assertFalse(offline.ready)
        assertEquals(DhuCheckStatus.Missing, offline.checks.first { it.id == "device" }.status)
    }

    @Test
    fun detectsHeadUnitServerListenPortInProcNet() {
        val listening = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
             0: 0100007F:149D 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1 00000000 100 0 0 10 0
             1: 00000000:01BB 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 2 1 00000000 100 0 0 10 0
        """.trimIndent()
        assertTrue(DhuDiscovery.isDevicePortListening(listening, 5277))
        assertTrue(DhuDiscovery.isDevicePortListening(listening, 443))
        assertFalse(DhuDiscovery.isDevicePortListening(listening, 80))

        val establishedOnly = """
             0: 0100007F:149D 0100007F:ABCD 01 00000000:00000000 00:00000000 00000000     0        0 1 1 00000000 100 0 0 10 0
        """.trimIndent()
        assertFalse(DhuDiscovery.isDevicePortListening(establishedOnly, 5277))
    }

    @Test
    fun writeConfigFileCreatesAndyOwnedIni() {
        val dir = createTempDirectory(prefix = "andy-dhu-test-").toFile()
        try {
            val file = DhuDiscovery.writeConfigFile(dir)
            assertTrue(file.isFile)
            val text = file.readText()
            assertTrue(text.contains("resolution = 800x480"))
            assertNotNull(file.parentFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingAutoDirIsNotExecutable() {
        assertNull(DhuDiscovery.findExecutable(null, isWindows = false, MapFs()))
        assertNull(DhuDiscovery.findExecutable("/missing", isWindows = true, MapFs()))
    }
}
