package app.andy.desktop.parser

import app.andy.model.AccessibilityNode
import app.andy.model.CrashKind
import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.LogLevel
import app.andy.model.AvdProfileCategory
import app.andy.model.VirtualDeviceType
import app.andy.model.WindowLayerInfo
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
    fun blankAccessibilityXmlReturnsNull() {
        assertNull(AndroidParsers.parseAccessibilityXml(""))
        assertNull(AndroidParsers.parseAccessibilityXml("   "))
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

    @Test
    fun parseWmSizePrefersOverrideOverPhysical() {
        val output = """
            Physical size: 1080x2400
            Override size: 1080x1920
        """.trimIndent()

        assertEquals("1080x1920", AndroidParsers.parseWmSize(output))
    }

    @Test
    fun parseWmSizeFallsBackToPhysicalWhenNoOverride() {
        assertEquals("1080x2400", AndroidParsers.parseWmSize("Physical size: 1080x2400\n"))
        assertNull(AndroidParsers.parseWmSize("not a size"))
    }

    @Test
    fun parseWmPhysicalSizeIgnoresRotatedOverride() {
        val output = """
            Physical size: 1080x2424
            Override size: 2424x1080
        """.trimIndent()

        assertEquals("1080x2424", AndroidParsers.parseWmPhysicalSize(output))
    }

    @Test
    fun parseDisplay0CurrentSizeReadsRotatedLogicalSize() {
        val output = """
            Display: mDisplayId=5
              init=674x1080 1dpi cur=674x1080 app=674x1080
            Display: mDisplayId=0 (organized)
              init=1200x1920 320dpi cur=1920x1200 app=1920x1200
                mCurrentRotation=ROTATION_270
            Display: mDisplayId=1
              init=100x100 1dpi cur=100x100 app=100x100
        """.trimIndent()

        assertEquals("1920x1200", AndroidParsers.parseDisplay0CurrentSize(output))
    }

    @Test
    fun parseDisplay0CurrentSizeIgnoresScrcpyDisplays() {
        val output = """
            Display: mDisplayId=9
              init=1080x674 1dpi cur=1080x674 app=1080x674
        """.trimIndent()

        assertNull(AndroidParsers.parseDisplay0CurrentSize(output))
    }

    @Test
    fun parseFocusedPackageFromWindowFocus() {
        val output = """
            mCurrentFocus=Window{abc u0 com.example.garden/.MainActivity}
            mFocusedApp=AppWindowToken{xyz token=Token{1 ActivityRecord{2 u0 com.other/.Ignored}}}
        """.trimIndent()

        assertEquals("com.example.garden", AndroidParsers.parseFocusedPackage(output))
    }

    @Test
    fun parseFocusedPackageFromTopResumedActivity() {
        val output = "topResumedActivity=ActivityRecord{abc u0 com.example.checkout/.CheckoutActivity t12}"

        assertEquals("com.example.checkout", AndroidParsers.parseFocusedPackage(output))
    }

    @Test
    fun parseFocusedPackageReturnsNullWhenMissing() {
        assertNull(AndroidParsers.parseFocusedPackage("no focus info here"))
    }

    // ---- B.2: dumpsys dropbox --------------------------------------------------------------

    @Test
    fun parsesDropboxIndexClassifyingCrashAnrAndWatchdogEntries() {
        val output = """
            ========================================
            2024-01-15 10:30:45 data_app_crash (text, 1234 bytes)
            Process: com.example.app
            Flags: 0x00000000
            Package: com.example.app v1
            Foreground: Yes
            Build: fingerprint/here
            java.lang.NullPointerException: Attempt to invoke virtual method
            at com.example.app.MainActivity.onCreate(MainActivity.java:42)
            ========================================
            2024-01-15 11:00:00 data_app_anr (text, 500 bytes)
            Process: com.example.app
            Subject: Input dispatching timed out
            ========================================
            2024-01-15 12:00:00 system_app_wtf (text, 200 bytes)
            Process: system_server
            Some WTF message here
        """.trimIndent()

        val parsed = AndroidParsers.parseDropboxIndex(output)
        val records = parsed.records

        assertEquals(3, records.size)
        assertEquals(CrashKind.JavaCrash, records[0].kind)
        assertEquals("com.example.app", records[0].packageName)
        assertTrue(records[0].id.startsWith("dropbox|"))
        assertTrue(records[0].id.endsWith("|data_app_crash"))
        assertTrue(records[0].summary.contains("NullPointerException"))
        assertTrue(records[0].timestampMillis > 0)

        assertEquals(CrashKind.Anr, records[1].kind)
        assertEquals("com.example.app", records[1].packageName)
        assertTrue(records[1].summary.contains("Input dispatching timed out"))

        assertEquals(CrashKind.Watchdog, records[2].kind)
        assertEquals("system_server", records[2].packageName)
        assertTrue(records[2].summary.contains("Some WTF message"))
    }

    @Test
    fun parseDropboxIndexReturnsEmptyForBlankOrUnrecognizedOutput() {
        assertEquals(emptyList(), AndroidParsers.parseDropboxIndex("").records)
        assertEquals(emptyList(), AndroidParsers.parseDropboxIndex("permission denied reading dropbox").records)
    }

    @Test
    fun parseDropboxIndexCachesFullEntryBodies() {
        val output = """
            ========================================
            2024-01-15 10:30:45 data_app_crash (text, 1234 bytes)
            Process: com.example.app
            java.lang.NullPointerException: boom
            at com.example.app.MainActivity.onCreate(MainActivity.java:42)
            ========================================
        """.trimIndent()

        val parsed = AndroidParsers.parseDropboxIndex(output)

        assertEquals(1, parsed.records.size)
        assertTrue(parsed.bodiesById.containsKey(parsed.records.single().id))
        assertTrue(parsed.bodiesById.values.single().contains("NullPointerException"))
    }

    @Test
    fun parseDropboxIndexAssignsUniqueIdsForDuplicateTimestampAndTag() {
        val output = """
            ========================================
            2026-08-03 13:16:33 clockpackage (text, 100 bytes)
            Process: com.example.clock
            java.lang.RuntimeException: first
            ========================================
            ========================================
            2026-08-03 13:16:33 clockpackage (text, 200 bytes)
            Process: com.example.clock
            java.lang.RuntimeException: second
        """.trimIndent()

        val parsed = AndroidParsers.parseDropboxIndex(output)
        val ids = parsed.records.map { it.id }

        assertEquals(2, parsed.records.size)
        assertEquals(listOf("dropbox|2026-08-03 13:16:33|clockpackage", "dropbox|2026-08-03 13:16:33|clockpackage#1"), ids)
        assertEquals(2, parsed.bodiesById.size)
    }

    @Test
    fun packagePidsFromPsMatchesTruncatedProcessNames() {
        val output = """
            PID   NAME
            1234  com.example.clo
            5678  system_server
        """.trimIndent()

        assertEquals(setOf("1234"), AndroidParsers.packagePidsFromPs(output, "com.example.clockpackage"))
    }

    @Test
    fun packagePidsFromPsArgsMatchesFullPackageInCommandLine() {
        val output = """
            PID   ARGS
            9012  com.example.clockpackage
            5678  system_server
        """.trimIndent()

        assertEquals(setOf("9012"), AndroidParsers.packagePidsFromPsArgs(output, "com.example.clockpackage"))
    }

    @Test
    fun processNameMatchingRejectsLongerSiblingPackageName() {
        assertFalse(AndroidParsers.processNameMatchesPackage("com.example.app2", "com.example.app"))
    }

    @Test
    fun packagePidsFromPsArgsRejectsSiblingPackageTokens() {
        val output = """
            PID   ARGS
            1111  com.example.app2
            2222  grep com.example.app
        """.trimIndent()

        assertEquals(emptySet(), AndroidParsers.packagePidsFromPsArgs(output, "com.example.app"))
    }

    @Test
    fun packagePidsFromPsArgsMatchesPackageSubprocessToken() {
        val output = """
            PID   ARGS
            3333  com.example.app:ui
        """.trimIndent()

        assertEquals(setOf("3333"), AndroidParsers.packagePidsFromPsArgs(output, "com.example.app"))
    }

    @Test
    fun parseDropboxEntryStripsChunkRuleAndTimestampHeader() {
        val output = """
            ========================================
            2024-01-15 10:30:45 data_app_crash (text, 1234 bytes)
            Process: com.example.app
            java.lang.NullPointerException: boom
            at com.example.app.MainActivity.onCreate(MainActivity.java:42)
            ========================================
        """.trimIndent()

        val entry = AndroidParsers.parseDropboxEntry(output)

        assertEquals(
            "Process: com.example.app\njava.lang.NullPointerException: boom\nat com.example.app.MainActivity.onCreate(MainActivity.java:42)",
            entry,
        )
    }

    // ---- B.3: dumpsys meminfo ---------------------------------------------------------------

    @Test
    fun parsesMeminfoBreakdownFromAppSummary() {
        val output = """
            Applications Memory Usage (in Kilobytes):
            Uptime: 123456 Realtime: 123456

            ** MEMINFO in pid 1234 [com.example.app] **
                               Pss  Private  Private  SwapPss     Heap     Heap     Heap
                             Total    Dirty    Clean    Dirty     Size    Alloc     Free
                            ------   ------   ------   ------   ------   ------   ------
              Native Heap    20480    20000        0        0    30720    25000     5720

            App Summary
                                       Pss(KB)                        Rss(KB)
                                        ------                         ------
                       Java Heap:     15360                          16384
                     Native Heap:     20480                          21504
                            Code:      5120
                           Stack:      1024
                        Graphics:      2048
                   Private Other:      3072
                          System:      4096
                           TOTAL:     51200                    TOTAL SWAP PSS:        0
        """.trimIndent()

        val breakdown = AndroidParsers.parseMeminfoBreakdown(output, "com.example.app")

        assertNotNull(breakdown)
        assertEquals("com.example.app", breakdown.packageName)
        assertEquals(15f, breakdown.javaHeapMb)
        assertEquals(20f, breakdown.nativeHeapMb)
        assertEquals(5f, breakdown.codeMb)
        assertEquals(1f, breakdown.stackMb)
        assertEquals(2f, breakdown.graphicsMb)
        assertEquals(3f, breakdown.privateOtherMb)
        assertEquals(4f, breakdown.systemMb)
        assertEquals(50f, breakdown.totalPssMb)
    }

    @Test
    fun meminfoBreakdownReturnsNullWhenNoRecognizedFields() {
        assertNull(AndroidParsers.parseMeminfoBreakdown("Permission denied", "com.example.app"))
    }

    // ---- B.4: dumpsys batterystats -----------------------------------------------------------

    @Test
    fun parsesBatteryStatsSummaryWakelocksAlarmsJobsAndDrain() {
        val output = """
            Wake lock MyWakeLock: 5m 30s realtime (12 times)
            Alarm com.example.app.ALARM_ACTION: 45 times
            Job com.example.app/.SyncJob: 2m 0s realtime (3 times)
            Estimated power use (mAh):
              Capacity: 3000
              Screen: 100.5
              com.example.app: 50.25
              com.other.app: 20.0

            Statistics since last charge:
              Some other data
        """.trimIndent()

        val summary = AndroidParsers.parseBatteryStatsSummary(output, "com.example.app")

        assertEquals(1, summary.wakelocks.size)
        assertEquals("MyWakeLock", summary.wakelocks[0].name)
        assertEquals("com.example.app", summary.wakelocks[0].packageName)
        assertEquals(330_000L, summary.wakelocks[0].heldMillis)
        assertEquals(12, summary.wakelocks[0].count)

        assertEquals(1, summary.alarms.size)
        assertEquals("com.example.app.ALARM_ACTION", summary.alarms[0].name)
        assertEquals(45, summary.alarms[0].count)

        assertEquals(1, summary.jobs.size)
        assertEquals("com.example.app/.SyncJob", summary.jobs[0].name)
        assertEquals(120_000L, summary.jobs[0].durationMillis)
        assertEquals(3, summary.jobs[0].count)

        assertEquals(2, summary.drain.size)
        assertEquals("com.example.app", summary.drain[0].packageName)
        assertEquals(50.25f, summary.drain[0].percent)
        assertEquals("com.other.app", summary.drain[1].packageName)
        assertEquals(20.0f, summary.drain[1].percent)

        assertEquals(output, summary.raw)
    }

    @Test
    fun batteryStatsSummaryWithNoMatchesReturnsEmptyListsButKeepsRaw() {
        val output = "no useful battery data here"

        val summary = AndroidParsers.parseBatteryStatsSummary(output, null)

        assertTrue(summary.wakelocks.isEmpty())
        assertTrue(summary.alarms.isEmpty())
        assertTrue(summary.jobs.isEmpty())
        assertTrue(summary.drain.isEmpty())
        assertEquals(output, summary.raw)
    }

    // ---- D.3: view hierarchy inspector -------------------------------------------------------

    @Test
    fun parsesActivityTopViewHierarchySkippingUnbracedDecorViewLine() {
        val output = """
            TASK 1234
              ACTIVITY com.example.garden/.MainActivity
              View Hierarchy:
                DecorView@a1b2c3d[MainActivity]
                  android.widget.LinearLayout{d4e5f60 V.E...... ........ 0,0-1080,2400} [com.example.garden/.MainActivity]
                    android.widget.FrameLayout{1a2b3c4 V.E...... ........ 0,0-1080,2280 #7f080058 app:id/content}
                      android.widget.TextView{9f8e7d6 V.E....... ........ 48,120-640,210 #7f0a0123 app:id/title}
                      android.widget.Button{0718290 VFE...C.. ........ 880,2100-1040,2260 #7f0a0456 app:id/add aid=1073741824}
        """.trimIndent()

        val root = AndroidParsers.parseActivityTopHierarchy(output)

        assertNotNull(root)
        assertEquals("android.widget.LinearLayout", root.className)
        assertEquals("[0,0][1080,2400]", root.bounds)
        assertEquals("com.example.garden/.MainActivity", root.attributes["view-activity"])
        assertEquals(1, root.children.size)

        val frame = root.children.single()
        assertEquals("android.widget.FrameLayout", frame.className)
        assertEquals("app:id/content", frame.resourceId)
        assertEquals("7f080058", frame.attributes["view-resource-id"])
        assertEquals(2, frame.children.size)

        val title = frame.children[0]
        assertEquals("app:id/title", title.resourceId)
        // Absolute bounds accumulate parent offsets; all ancestors sit at (0,0) here.
        assertEquals("[48,120][640,210]", title.bounds)
        assertFalse(title.clickable)
        assertTrue(title.visible)
        assertTrue(title.enabled)

        val addButton = frame.children[1]
        assertEquals("app:id/add", addButton.resourceId)
        assertEquals("[880,2100][1040,2260]", addButton.bounds)
        assertTrue(addButton.clickable)
        assertTrue(addButton.focusable)
        assertEquals("0718290", addButton.attributes["view-hash"])
        assertEquals("1073741824", addButton.attributes["view-aid"])
    }

    @Test
    fun parseActivityTopHierarchyReturnsNullWithoutViewHierarchySection() {
        assertNull(AndroidParsers.parseActivityTopHierarchy(""))
        assertNull(AndroidParsers.parseActivityTopHierarchy("TASK 1234\n  ACTIVITY com.example/.Main"))
    }

    @Test
    fun mergeViewHierarchyCopiesViewAttributesOntoMatchingUiautomatorNodesOnly() {
        val uiautomatorRoot = AccessibilityNode(
            id = "root",
            className = "android.widget.FrameLayout",
            resourceId = "root",
            text = null,
            contentDescription = null,
            bounds = "[0,0][1080,2400]",
            clickable = false,
            focusable = false,
            enabled = true,
            children = listOf(
                AccessibilityNode(
                    id = "btn",
                    className = "android.widget.Button",
                    resourceId = "com.example.garden:id/add",
                    text = null,
                    contentDescription = "Add plant",
                    bounds = "[880,2100][1040,2260]",
                    clickable = true,
                    focusable = true,
                    enabled = true,
                ),
                AccessibilityNode(
                    id = "orphan",
                    className = "android.widget.TextView",
                    resourceId = "com.example.garden:id/orphan",
                    text = "Orphan",
                    contentDescription = null,
                    bounds = "[10,10][20,20]",
                    clickable = false,
                    focusable = false,
                    enabled = true,
                ),
            ),
        )
        val activityTopRoot = AccessibilityNode(
            id = "activity-top.0",
            className = "android.widget.FrameLayout",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = "[0,0][1080,2400]",
            clickable = false,
            focusable = false,
            enabled = true,
            attributes = mapOf("view-hash" to "root123"),
            children = listOf(
                AccessibilityNode(
                    id = "activity-top.1",
                    className = "android.widget.Button",
                    resourceId = null,
                    text = null,
                    contentDescription = null,
                    bounds = "[880,2100][1040,2260]",
                    clickable = true,
                    focusable = true,
                    enabled = true,
                    attributes = mapOf("view-hash" to "0718290", "view-aid" to "1073741824"),
                ),
            ),
        )

        val merged = AndroidParsers.mergeViewHierarchy(uiautomatorRoot, activityTopRoot)

        assertNotNull(merged)
        assertEquals("root123", merged.attributes["view-hash"])
        assertEquals("true", merged.attributes["view-matched"])

        val mergedBtn = merged.children.first { it.id == "btn" }
        assertEquals("0718290", mergedBtn.attributes["view-hash"])
        assertEquals("1073741824", mergedBtn.attributes["view-aid"])
        assertEquals("true", mergedBtn.attributes["view-matched"])
        assertEquals("Add plant", mergedBtn.contentDescription) // uiautomator-only field untouched.

        val mergedOrphan = merged.children.first { it.id == "orphan" }
        assertNull(mergedOrphan.attributes["view-matched"])
        assertEquals("Orphan", mergedOrphan.text)
    }

    @Test
    fun mergeViewHierarchyReturnsTheNonNullSideWhenOneTreeIsMissing() {
        val onlyUiautomator = AccessibilityNode(
            id = "root", className = "android.widget.FrameLayout", resourceId = "root",
            text = null, contentDescription = null, bounds = "[0,0][1080,2400]",
            clickable = false, focusable = false, enabled = true,
        )
        assertEquals(onlyUiautomator, AndroidParsers.mergeViewHierarchy(onlyUiautomator, null))
        assertEquals(onlyUiautomator, AndroidParsers.mergeViewHierarchy(null, onlyUiautomator))
        assertNull(AndroidParsers.mergeViewHierarchy(null, null))
    }

    @Test
    fun parseActivityTopScrollOffsetsReadsMScrollYPerViewHash() {
        val output = """
            View Hierarchy:
              android.widget.ScrollView{a1b2c3 V.E...... ........ 0,200-1080,2200}
                mScrollY=300
              android.widget.TextView{d4e5f60 V.E...... ........ 48,528-1032,608}
        """.trimIndent()

        val offsets = AndroidParsers.parseActivityTopScrollOffsets(output)

        assertEquals(mapOf("a1b2c3" to 300), offsets)
    }

    @Test
    fun attachScrollOffsetsFromActivityTopCopiesScrollYOntoNodes() {
        val output = """
            View Hierarchy:
              android.widget.ScrollView{a1b2c3 V.E...... ........ 0,200-1080,2200}
                mScrollY=128
                android.widget.TextView{d4e5f60 V.E...... ........ 48,528-1032,608}
        """.trimIndent()

        val root = AndroidParsers.parseActivityTopHierarchy(output)
        val enriched = AndroidParsers.attachScrollOffsetsFromActivityTop(output, root)

        assertNotNull(enriched)
        assertEquals("128", enriched.attributes["scroll-y"])
        assertNull(enriched.children.single().attributes["scroll-y"])
    }

    @Test
    fun mergeViewHierarchyCopiesScrollYOntoMatchingScrollableNodes() {
        val uiautomatorRoot = AccessibilityNode(
            id = "root",
            className = "android.widget.FrameLayout",
            resourceId = "root",
            text = null,
            contentDescription = null,
            bounds = "[0,0][1080,2340]",
            clickable = false,
            focusable = false,
            enabled = true,
            children = listOf(
                AccessibilityNode(
                    id = "scroll",
                    className = "android.widget.ScrollView",
                    resourceId = null,
                    text = null,
                    contentDescription = null,
                    bounds = "[0,200][1080,2200]",
                    clickable = false,
                    focusable = false,
                    enabled = true,
                    scrollable = true,
                    children = listOf(
                        AccessibilityNode(
                            id = "text",
                            className = "android.widget.TextView",
                            resourceId = "app:id/title",
                            text = "Hello",
                            contentDescription = null,
                            bounds = "[48,528][1032,608]",
                            clickable = false,
                            focusable = false,
                            enabled = true,
                        ),
                    ),
                ),
            ),
        )
        val activityTopRoot = AccessibilityNode(
            id = "activity-top.0",
            className = "android.widget.ScrollView",
            resourceId = null,
            text = null,
            contentDescription = null,
            bounds = "[0,200][1080,2200]",
            clickable = false,
            focusable = false,
            enabled = true,
            scrollable = true,
            attributes = mapOf("view-hash" to "a1b2c3", "scroll-y" to "300"),
            children = emptyList(),
        )

        val merged = AndroidParsers.mergeViewHierarchy(uiautomatorRoot, activityTopRoot)

        assertNotNull(merged)
        val mergedScroll = merged.children.single()
        assertEquals("300", mergedScroll.attributes["scroll-y"])
        assertEquals("a1b2c3", mergedScroll.attributes["view-hash"])
    }

    @Test
    fun parsesDumpsysWindowZOrderWithFrameTypeAndVisibility() {
        val output = """
            WINDOW MANAGER WINDOWS (dumpsys window windows)
              Window #0 Window{a1b2c3d u0 com.example.garden/com.example.garden.MainActivity}:
                mDisplayId=0
                mAttrs=WM.LayoutParams{... ty=BASE_APPLICATION ...}
                frame=[0,0][1080,2400]
                isVisible=true isOnScreen=true
              Window #1 Window{9988776 InputMethod}:
                mDisplayId=0
                mAttrs=WM.LayoutParams{... ty=INPUT_METHOD ...}
                frame=[0,1900][1080,2400]
                isVisible=false isOnScreen=false
              Window #2 Window{5544332 u0 StatusBar}:
                mDisplayId=0
                mAttrs=WM.LayoutParams{... ty=STATUS_BAR ...}
                frame=[0,0][1080,80]
                isVisible=true isOnScreen=true
        """.trimIndent()

        val windows = AndroidParsers.parseDumpsysWindow(output)

        assertEquals(3, windows.size)
        assertEquals(
            WindowLayerInfo(
                index = 0,
                title = "com.example.garden/com.example.garden.MainActivity",
                packageName = "com.example.garden",
                displayId = 0,
                bounds = "[0,0][1080,2400]",
                windowType = "BASE_APPLICATION",
                isVisible = true,
                isOnScreen = true,
            ),
            windows[0],
        )
        assertNull(windows[1].packageName)
        assertEquals("INPUT_METHOD", windows[1].windowType)
        assertFalse(windows[1].isVisible)
        assertEquals("[0,0][1080,80]", windows[2].bounds)
        assertEquals("STATUS_BAR", windows[2].windowType)
    }

    @Test
    fun parseDumpsysWindowReturnsEmptyListWithoutWindowHeaders() {
        assertEquals(emptyList(), AndroidParsers.parseDumpsysWindow(""))
        assertEquals(emptyList(), AndroidParsers.parseDumpsysWindow("permission denied"))
    }
}
