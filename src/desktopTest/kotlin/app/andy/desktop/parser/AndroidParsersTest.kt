package app.andy.desktop.parser

import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        assertEquals(DeviceKind.Emulator, devices[1].kind)
        assertEquals(DeviceConnectionState.Offline, devices[1].state)
        assertEquals(DeviceConnectionState.Unauthorized, devices[2].state)
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
}
