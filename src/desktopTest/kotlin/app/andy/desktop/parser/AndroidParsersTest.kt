package app.andy.desktop.parser

import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.LogLevel
import app.andy.model.AvdProfileCategory
import app.andy.model.VirtualDeviceType
import app.andy.model.isMdnsAdbSerial
import app.andy.model.isWifiIpSerial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidParsersTest {
    @Test
    fun parsesAdbDevicesWithFields() {
        val output = """
            List of devices attached
            R3CXB056ZZB	device product:e3q model:SM_S921U device:e3q transport_id:4
            emulator-5554	offline
            ABC123	unauthorized
        """.trimIndent()

        val devices = AndroidParsers.parseAdbDevices(output)

        assertEquals(3, devices.size)
        assertEquals("R3CXB056ZZB", devices[0].serial)
        assertEquals("SM S921U", devices[0].displayName)
        assertEquals(DeviceKind.Physical, devices[0].kind)
        assertEquals(DeviceConnectionState.Online, devices[0].state)
        assertEquals(app.andy.model.DeviceTransport.Usb, devices[0].transport)
        assertEquals(DeviceKind.Emulator, devices[1].kind)
        assertEquals(DeviceConnectionState.Offline, devices[1].state)
        assertEquals(app.andy.model.DeviceTransport.Unknown, devices[1].transport)
        assertEquals(DeviceConnectionState.Unauthorized, devices[2].state)
    }

    @Test
    fun classifiesWifiTransportFromIpPortSerial() {
        val output = """
            List of devices attached
            192.168.86.47:5555	device product:e3q model:SM_S921U device:e3q transport_id:4
            emulator-5554	device
            R3CXB056ZZB	device product:e3q model:SM_S921U device:e3q transport_id:5
        """.trimIndent()

        val devices = AndroidParsers.parseAdbDevices(output)

        assertEquals(app.andy.model.DeviceTransport.Wifi, devices[0].transport)
        assertEquals(DeviceKind.Physical, devices[0].kind)
        assertEquals(app.andy.model.DeviceTransport.Unknown, devices[1].transport)
        assertEquals(app.andy.model.DeviceTransport.Usb, devices[2].transport)
    }

    @Test
    fun keepsWifiIpAndMdnsAliasUntilHardwareIdsAreKnown() {
        // Parse-time rows only have a hardware id on the mDNS serial, so aliases stay until
        // DesktopDeviceService enriches IP devices with ro.serialno and dedupes again.
        val output = """
            List of devices attached
            192.168.86.150:35923	device product:blazer model:Pixel_10_Pro device:blazer transport_id:3
            adb-5A080DLCH000UR-oVigq2._adb-tls-connect._tcp	device product:blazer model:Pixel_10_Pro device:blazer transport_id:4
            emulator-5554	device product:sdk_gphone64_arm64 model:Pixel_9 device:emu64a transport_id:1
        """.trimIndent()

        val devices = AndroidParsers.parseAdbDevices(output)

        assertEquals(3, devices.size)
        assertEquals(
            setOf(
                "192.168.86.150:35923",
                "adb-5A080DLCH000UR-oVigq2._adb-tls-connect._tcp",
                "emulator-5554",
            ),
            devices.map { it.serial }.toSet(),
        )
        assertEquals("5A080DLCH000UR-oVigq2", devices.first { isMdnsAdbSerial(it.serial) }.hardwareId)
        assertNull(devices.first { isWifiIpSerial(it.serial) }.hardwareId)
    }

    @Test
    fun keepsDistinctSameModelWifiDevicesWithoutSharedHardwareId() {
        val output = """
            List of devices attached
            192.168.86.150:35923	device product:blazer model:Pixel_10_Pro device:blazer transport_id:3
            192.168.86.200:5555	device product:e3q model:SM_S921U device:e3q transport_id:4
            adb-OTHERSERIAL._adb-tls-connect._tcp	device product:e3q model:SM_S921U device:e3q transport_id:5
        """.trimIndent()

        val devices = AndroidParsers.parseAdbDevices(output)

        assertEquals(3, devices.size)
        assertEquals(
            setOf("192.168.86.150:35923", "192.168.86.200:5555", "adb-OTHERSERIAL._adb-tls-connect._tcp"),
            devices.map { it.serial }.toSet(),
        )
    }

    @Test
    fun parsesMdnsServices() {
        val output = """
            List of discovered mdns services
            adb-VAN10A203710441	_adb._tcp	192.168.86.47:5555
            adb-VAN10A203710441	_adb-tls-connect._tcp	192.168.86.47:37123
            adb-PAIRING	_adb-tls-pairing._tcp	192.168.86.47:37199
        """.trimIndent()

        val services = AndroidParsers.parseMdnsServices(output)

        assertEquals(3, services.size)
        assertEquals("adb-VAN10A203710441", services[0].instanceName)
        assertEquals("_adb._tcp", services[0].serviceType)
        assertEquals("192.168.86.47", services[0].host)
        assertEquals(5555, services[0].port)
        assertTrue(services[0].isConnect)
        assertTrue(services[1].isConnect)
        assertTrue(services[2].isPairing)
        assertFalse(services[2].isConnect)
    }

    @Test
    fun parsesThreadtimeLogcatLine() {
        val entry = AndroidParsers.parseLogcatLine("07-07 09:36:39.683 12345 12346 E BatteryStats: Invalid uid for waking network packet: -1")

        assertNotNull(entry)
        assertEquals("07-07 09:36:39.683", entry.time)
        assertEquals("12345", entry.pid)
        assertEquals(LogLevel.Error, entry.level)
        assertEquals("BatteryStats", entry.tag)
        assertTrue(entry.message.contains("Invalid uid"))
    }

    @Test
    fun extractsPackageFilterFromLogcatSearch() {
        val (packageName, search) = AndroidParsers.extractPackageFilter("auth package:com.phoebe.debug failure")

        assertEquals("com.phoebe.debug", packageName)
        assertEquals("auth failure", search)
    }

    @Test
    fun parsesSystemImagesFromSdkManagerOutput() {
        val output = """
            system-images;android-36;google_apis;arm64-v8a | 7 | Google APIs ARM 64 v8a System Image | Installed
            system-images;android-35;google_apis_playstore;arm64-v8a | 6 | Google Play ARM 64 v8a System Image | Available
        """.trimIndent()

        val images = AndroidParsers.parseSystemImages(output)

        assertEquals(2, images.size)
        assertEquals("36", images[0].api)
        assertEquals("google_apis", images[0].variant)
        assertEquals("arm64-v8a", images[0].abi)
        assertTrue(images[0].installed)
    }

    @Test
    fun parsesAvdProfilesDevicesAndSnapshots() {
        val profilesOutput = """
            id: 34 or "pixel_8"
                Name: Pixel 8
                OEM : Google
                Screen: 1080 x 2400
                dpis : 420
            id: 51 or "pixel_fold"
                Name: Pixel Fold
                OEM : Google
                Screen: 1840 x 2208
                dpis : 420
            id: 12 or "wear_os_square"
                Name: Wear OS Square
                OEM : Google
                Screen: 384 x 384
                dpis : 320
        """.trimIndent()
        val avdOutput = """
            Name: Pixel_8_API_36
            Path: /tmp/Pixel_8_API_36.avd
            Target: Google APIs (Google Inc.)
            ABI: arm64-v8a
            API level: 36
        """.trimIndent()

        val profiles = AndroidParsers.parseProfiles(profilesOutput)
        val avds = AndroidParsers.parseAvdList(avdOutput)
        val snapshots = AndroidParsers.parseSnapshots("default_boot\nmanual\n", "Pixel_8_API_36")

        assertEquals(AvdProfileCategory.Phone, profiles[0].category)
        assertEquals(AvdProfileCategory.Foldable, profiles[1].category)
        assertEquals(AvdProfileCategory.Watch, profiles[2].category)
        assertEquals(36, avds.single().apiLevel)
        assertEquals(VirtualDeviceType.Phone, avds.single().deviceType)
        assertEquals(listOf("default_boot", "manual"), snapshots.map { it.name })
    }

    @Test
    fun parsesDetailedEmulatorSnapshotTable() {
        val output = """
            List of snapshots present on all disks:
            ID        TAG                 VM SIZE                DATE       VM CLOCK
            1         default_boot        0 B                    2026-07-07 00:00:00
            2         manual              12.5 MB                2026-07-07 00:01:00
            OK
        """.trimIndent()

        val snapshots = AndroidParsers.parseSnapshots(output, "Pixel_8_API_36")

        assertEquals(listOf("default_boot", "manual"), snapshots.map { it.name })
    }

    @Test
    fun parsesMultipleAvdsSeparatedByAvdManagerRules() {
        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6
              Device: pixel_6 (Google)
                Path: /Users/joer/.android/avd/Pixel_6.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 17.0 ("CinnamonBun") Tag/ABI: google_apis/arm64-v8a
                Skin: pixel_6
              Sdcard: 512M
            ---------
                Name: Pixel_8
              Device: pixel_8 (Google)
                Path: /Users/joer/.android/avd/Pixel_8.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 17.0 ("CinnamonBun") Tag/ABI: google_apis/arm64-v8a
                Skin: pixel_8
              Sdcard: 512M
            ---------
                Name: Pixel_9
              Device: pixel_9 (Google)
                Path: /Users/joer/.android/avd/Pixel_9.avd
              Target: Google APIs PlayStore (Google Inc.)
                      Based on: Android 17.0 ("CinnamonBun") Tag/ABI: google_apis_playstore/arm64-v8a
                Skin: pixel_9
              Sdcard: 512M
        """.trimIndent()

        val avds = AndroidParsers.parseAvdList(output)

        assertEquals(listOf("Pixel_6", "Pixel_8", "Pixel_9"), avds.map { it.name })
        assertEquals(listOf(17, 17, 17), avds.map { it.apiLevel })
        assertEquals(listOf(VirtualDeviceType.Phone, VirtualDeviceType.Phone, VirtualDeviceType.Phone), avds.map { it.deviceType })
    }

    @Test
    fun parsesAccessibilityXmlTree() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
              <node index="0" text="" resource-id="root" class="android.widget.FrameLayout" package="com.example" bounds="[0,0][1080,2400]" clickable="false" focusable="false" enabled="true" selected="false" scrollable="false">
                <node index="0" text="Sign in" content-desc="Sign in button" resource-id="com.example:id/sign_in" class="android.widget.Button" package="com.example" bounds="[32,100][240,160]" clickable="true" long-clickable="true" focusable="true" focused="false" enabled="true" checkable="false" checked="false" selected="false" scrollable="false" password="false" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val root = AndroidParsers.parseAccessibilityXml(xml)

        assertNotNull(root)
        assertEquals("android.widget.FrameLayout", root.className)
        assertEquals("com.example", root.packageName)
        assertEquals(1, root.children.size)
        assertEquals("Sign in", root.children.single().text)
        assertEquals("[32,100][240,160]", root.children.single().bounds)
        assertTrue(root.children.single().longClickable)
        assertTrue(root.children.single().visible)
    }

    @Test
    fun parsesPackagePermissionsAndActivities() {
        val output = """
            requested permissions:
              android.permission.CAMERA
              android.permission.POST_NOTIFICATIONS
            runtime permissions:
              android.permission.CAMERA: granted=true, flags=[ USER_SET]
              android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET]
            Activity Resolver Table:
              com.example/.MainActivity filter 123
              com.example/com.example.SettingsActivity filter 456
        """.trimIndent()

        val permissions = AndroidParsers.parsePackagePermissions(output)
        val activities = AndroidParsers.parsePackageActivities("com.example", output)

        assertEquals(2, permissions.size)
        assertEquals(true, permissions[0].granted)
        assertEquals(false, permissions[1].granted)
        assertEquals(".MainActivity", activities[0].name)
        assertEquals("com.example.SettingsActivity", activities[1].name)
    }

    @Test
    fun parsesAppBuildAndInstallDetails() {
        val output = """
            versionName=2026.0709.1406-debug
            versionCode=394 minSdk=26 targetSdk=36
            signatures=PackageSignatures{abc version:2, signatures:[abcdef]}
            pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA DEBUGGABLE ]
        """.trimIndent()

        val details = AndroidParsers.parseAppDetails(output)

        assertEquals("2026.0709.1406-debug", details.versionName)
        assertEquals("394", details.versionCode)
        assertEquals("26", details.minSdk)
        assertEquals("36", details.targetSdk)
        assertEquals("v2", details.signingScheme)
        assertEquals(true, details.debuggable)
    }

    @Test
    fun parsesProcessMetricsFromTop() {
        val output = """
            Tasks: 303 total,   1 running, 302 sleeping,   0 stopped,   0 zombie
            400%cpu  44%user   0%nice  48%sys 296%idle   4%iow
              PID %CPU  RES ARGS
             1158 12.5 169M com.android.settings
              432  0.0 8.9M zygote64
        """.trimIndent()

        val processes = AndroidParsers.parseProcessMetrics(output)

        assertEquals("1158", processes.first().pid)
        assertEquals("com.android.settings", processes.first().name)
        assertEquals(12.5f, processes.first().cpuPercent)
        assertEquals(169f, processes.first().memoryMb)
    }

    @Test
    fun parsesFrameStats() {
        val output = """
            Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted
            0,1000000000,1000000000,0,0,0,0,0,0,0,0,0,0,1010000000,0,0,0
            0,2000000000,2000000000,0,0,0,0,0,0,0,0,0,0,2025000000,0,0,0
        """.trimIndent()

        val frames = AndroidParsers.parseFrameStats(output)

        assertEquals(2, frames.size)
        assertEquals(10f, frames[0].millis)
        assertEquals(25f, frames[1].millis)
    }

    @Test
    fun parsesModernFrameStatsHeader() {
        val output = """
            Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameStartTime,FrameInterval,WorkloadTarget,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,CommandSubmissionCompleted,
            0,5589902,132400881643362,132400881643362,0,0,0,0,0,0,0,0,0,0,0,0,0,132400888587512,0,0,0,0,0,0,
        """.trimIndent()

        val frames = AndroidParsers.parseFrameStats(output)

        assertEquals(1, frames.size)
        assertEquals(6.94415f, frames[0].millis)
    }

    @Test
    fun parsesFrameStatsVsyncGapForFpsCalculation() {
        val output = """
            Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted
            0,1000000000,1000000000,0,0,0,0,0,0,0,0,0,0,1010000000,0,0,0
            0,1016666666,1016666666,0,0,0,0,0,0,0,0,0,0,1030000000,0,0,0
            0,1033333333,1033333333,0,0,0,0,0,0,0,0,0,0,1045000000,0,0,0
        """.trimIndent()

        val frames = AndroidParsers.parseFrameStats(output)

        assertEquals(3, frames.size)
        assertNull(frames[0].vsyncGapMillis)
        assertEquals(16.666666f, frames[1].vsyncGapMillis)
        assertEquals(16.666667f, frames[2].vsyncGapMillis)
        val fps = frames.mapNotNull { it.vsyncGapMillis }.let { gaps -> 1000f / (gaps.sum() / gaps.size) }
        assertTrue(fps in 59f..61f)
    }

    @Test
    fun parsesNetworkTotalsExcludingLoopback() {
        val output = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo:  140223    2534    0    0    0     0          0         0   140223    2534    0    0    0     0       0          0
              eth0:   83535     364    0    0    0     0          0         0    43458     362    0    0    0     0       0          0
             wlan0:  937773    3948    0    0    0     0          0         0   510544    3000    0    0    0     0       0          0
        """.trimIndent()

        val totals = AndroidParsers.parseNetworkTotals(output)

        assertNotNull(totals)
        assertEquals(83535L + 937773L, totals!!.first)
        assertEquals(43458L + 510544L, totals.second)
    }

    @Test
    fun parsesNetworkTotalsReturnsNullWhenNoInterfaces() {
        val totals = AndroidParsers.parseNetworkTotals("cat: /proc/net/dev: Permission denied")

        assertEquals(null, totals)
    }
}
