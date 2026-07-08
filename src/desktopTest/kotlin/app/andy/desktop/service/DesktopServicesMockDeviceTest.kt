package app.andy.desktop.service

import app.andy.model.DeviceConnectionState
import app.andy.model.DeviceKind
import app.andy.model.ExtraType
import app.andy.model.IntentDraft
import app.andy.model.IntentExtra
import app.andy.model.LogLevel
import app.andy.model.ProxyRule
import app.andy.model.AvdCameraOption
import app.andy.model.AvdCreationConfig
import app.andy.model.VirtualDeviceType
import kotlinx.coroutines.flow.filter
import app.andy.service.LogcatFilter
import app.andy.service.MirrorInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopServicesMockDeviceTest {
    @Test
    fun scrcpyServerLocatorUsesBundledResource() {
        val originalHome = System.getProperty("user.home")
        val testHome = kotlin.io.path.createTempDirectory("andy-scrcpy-test-home").toFile()
        val serverInfo = try {
            System.setProperty("user.home", testHome.absolutePath)
            ScrcpyServerLocator.find()?.let { server ->
                Triple(server.absolutePath, server.isFile, server.length())
            }
        } finally {
            System.setProperty("user.home", originalHome)
            testHome.deleteRecursively()
        }

        assertNotNull(serverInfo)
        assertTrue(serverInfo.second)
        assertTrue(serverInfo.third > 100_000)
        assertEquals(File(testHome, ".andy/scrcpy/scrcpy-server").absolutePath, serverInfo.first)
    }

    @Test
    fun deviceDiscoveryListsAndEnrichesMockDevices() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val sdk = services.devices.discoverSdk()
        val devices = services.devices.listDevices()

        assertTrue(sdk.hasAdb)
        assertTrue(sdk.hasEmulatorTools)
        assertEquals(4, devices.size)
        assertEquals("Pixel_8_API_36", devices[0].displayName)
        assertEquals(DeviceKind.Emulator, devices[0].kind)
        assertEquals(DeviceConnectionState.Online, devices[0].state)
        assertEquals("36", devices[0].apiLevel)
        assertEquals(87, devices[0].batteryPercent)
        assertEquals("1080x2400", devices[0].screenSize)
        assertEquals("60G free / 112G", devices[0].storageSummary)
        assertEquals("Galaxy S24", devices[1].displayName)
        assertEquals(DeviceKind.Physical, devices[1].kind)
        assertEquals(DeviceConnectionState.Offline, devices[2].state)
        assertEquals(DeviceConnectionState.Unauthorized, devices[3].state)

        val shell = services.devices.shell("emulator-5554", listOf("wm", "size"))

        assertTrue(shell.isSuccess)
        assertTrue(shell.stdout.contains("1080x2400"))
        assertTrue(env.ran("-s", "emulator-5554", "shell", "wm", "size"))
    }

    @Test
    fun avdCatalogAndLifecycleUseMockSdkTools() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val images = services.avd.listSystemImages()
        val profiles = services.avd.listProfiles()
        val avds = services.avd.listVirtualDevices()
        val created = services.avd.createVirtualDevice(
            name = "Pixel_8_API_36",
            profileId = "34",
            systemImagePackage = "system-images;android-36;google_apis;arm64-v8a",
        )
        val advancedCreated = services.avd.createVirtualDevice(
            AvdCreationConfig(
                name = "Pixel_8_API_36",
                profileId = "34",
                systemImagePackage = "system-images;android-36;google_apis;arm64-v8a",
                ramMb = 4096,
                storageMb = 16384,
                cpuCores = 6,
                gpuMode = "swiftshader_indirect",
                backCamera = AvdCameraOption.Webcam0,
                startAfterCreate = false,
            ),
        )
        val installedImage = services.avd.installSystemImage("system-images;android-35;google_apis_playstore;arm64-v8a")
        val uninstalledImage = services.avd.uninstallSystemImage("system-images;android-36;google_apis;arm64-v8a")
        val snapshots = services.avd.listSnapshots("Pixel_8_API_36")
        val savedSnapshot = services.avd.saveSnapshot("Pixel_8_API_36", "manual")
        val restoredSnapshot = services.avd.restoreSnapshot("Pixel_8_API_36", "manual")
        val deletedSnapshot = services.avd.deleteSnapshot("Pixel_8_API_36", "manual")
        val cloned = services.avd.cloneVirtualDevice("Pixel_8_API_36", "Pixel_8_API_36_Copy")
        val deleted = services.avd.deleteVirtualDevice("Pixel_8_API_36")
        val started = services.avd.startVirtualDevice("Pixel_8_API_36")
        val stopped = services.avd.stopVirtualDevice("Pixel_8_API_36")

        assertEquals(listOf("system-images;android-36;google_apis;arm64-v8a", "system-images;android-35;google_apis_playstore;arm64-v8a"), images.map { it.packageId })
        assertTrue(images.first().installed)
        assertEquals("Pixel 8", profiles.single().name)
        assertEquals("Pixel_8_API_36", avds.single().name)
        assertEquals(36, avds.single().apiLevel)
        assertEquals(VirtualDeviceType.Phone, avds.single().deviceType)
        assertTrue(created.isSuccess)
        assertTrue(advancedCreated.isSuccess)
        assertTrue(installedImage.isSuccess)
        assertTrue(uninstalledImage.isSuccess)
        assertEquals(listOf("default_boot", "manual"), snapshots.map { it.name })
        assertTrue(savedSnapshot.isSuccess)
        assertTrue(restoredSnapshot.isSuccess)
        assertTrue(deletedSnapshot.isSuccess)
        assertTrue(cloned.isSuccess)
        assertTrue(deleted.isSuccess)
        assertTrue(started.isSuccess)
        assertTrue(stopped.isSuccess)
        assertTrue(stopped.stdout.contains("emulator-5554"))
        assertTrue(env.ran("create", "avd", "-n", "Pixel_8_API_36"))
        assertTrue(env.ran("--install", "system-images;android-35;google_apis_playstore;arm64-v8a"))
        assertTrue(env.ran("--uninstall", "system-images;android-36;google_apis;arm64-v8a"))
        assertTrue(env.ran("avd", "snapshot", "save", "manual"))
        assertTrue(env.ran("avd", "snapshot", "load", "manual"))
        assertTrue(env.ran("avd", "snapshot", "delete", "manual"))
        assertTrue(env.ran("delete", "avd", "-n", "Pixel_8_API_36"))
        assertTrue(env.ran("-s", "emulator-5554", "emu", "kill"))
    }

    @Test
    fun mirrorFallbackInputAndScreenshotUseMockDevice() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val tap = services.mirror.sendInput(MirrorInput.Tap(44, 55))
        val down = services.mirror.sendInput(MirrorInput.Touch(app.andy.service.MirrorTouchAction.Down, 44, 55))
        val swipe = services.mirror.sendInput(MirrorInput.Swipe(1, 2, 3, 4, 250))
        val text = services.mirror.sendInput(MirrorInput.Text("hello world"))
        val back = services.mirror.sendInput(MirrorInput.Back)
        val screenshot = services.mirror.screenshot("emulator-5554")

        assertTrue(tap.isSuccess)
        assertTrue(down.isSuccess)
        assertTrue(down.stdout.contains("ignored"))
        assertTrue(swipe.isSuccess)
        assertTrue(text.isSuccess)
        assertTrue(back.isSuccess)
        assertEquals("PNGDATA", screenshot?.decodeToString())
        assertTrue(env.ran("input", "tap", "44", "55"))
        assertTrue(env.ran("input", "text", "hello%sworld"))
        assertTrue(env.ran("exec-out", "screencap", "-p"))
    }

    @Test
    fun logcatSnapshotAppliesSearchLevelAndPackageFilters() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val entries = services.logcat.snapshot(
            serial = "emulator-5554",
            filter = LogcatFilter(search = "failure package:com.example.app", levels = setOf(LogLevel.Warn, LogLevel.Error)),
            limit = 50,
        )

        assertEquals(1, entries.size)
        assertEquals("Example", entries.single().tag)
        assertEquals(LogLevel.Warn, entries.single().level)
        assertEquals("5678", entries.single().pid)
        assertTrue(env.ran("pidof", "com.example.app"))
    }

    @Test
    fun intentsAppsFilesProxyMetricsAndAccessibilityWorkAgainstMockDevice() = runBlocking {
        val env = MockAndroidDeviceEnvironment()
        val services = env.services()

        val draft = IntentDraft(
            component = "com.example.app/.MainActivity",
            dataUri = "andy://open/42",
            extras = listOf(
                IntentExtra("name", ExtraType.StringValue, "Andy"),
                IntentExtra("enabled", ExtraType.BooleanValue, "true"),
            ),
        )
        val builtIntent = services.intents.buildCommand(draft)
        val sentIntent = services.intents.send("emulator-5554", draft)

        val apps = services.apps.listApps("emulator-5554")
        val launched = services.apps.launch("emulator-5554", "com.example.app")
        val stopped = services.apps.stop("emulator-5554", "com.example.app")
        val cleared = services.apps.clearData("emulator-5554", "com.example.app")
        val reset = services.apps.resetPermissions("emulator-5554", "com.example.app")
        val uninstalled = services.apps.uninstall("emulator-5554", "com.example.app")
        val permissions = services.apps.listPermissions("emulator-5554", "com.example.app")
        val activities = services.apps.listActivities("emulator-5554", "com.example.app")

        val files = services.files.list("emulator-5554", "/sdcard/Download")
        val pulled = services.files.pull("emulator-5554", "/sdcard/Download/report.txt", "/tmp/report.txt")
        val pushed = services.files.push("emulator-5554", "/tmp/report.txt", "/sdcard/Download/report.txt")
        val deleted = services.files.delete("emulator-5554", "/sdcard/Download/old.txt")

        val proxyHost = services.proxy.resolveDeviceProxyHost("emulator-5554")
        val proxyConfigured = services.proxy.configureDeviceProxy("emulator-5554", proxyHost, 8888)
        val proxyStarted = services.proxy.start(
            8888,
            listOf(
                ProxyRule(
                    id = "rule-1",
                    name = "JSON override",
                    enabled = true,
                    urlPattern = "example.test",
                    method = "GET",
                    statusCode = 201,
                    setHeaders = mapOf("x-andy" to "yes"),
                    removeHeaders = listOf("etag"),
                    responseBody = """{"ok":true}""",
                ),
            ),
        )
        val exchange = services.proxy.exchanges.filter { it.isNotEmpty() }.first().single()
        val proxyCleared = services.proxy.clearDeviceProxy("emulator-5554")
        val proxyStopped = services.proxy.stop()

        val metric = services.metrics.stream("emulator-5554", "com.example.app").take(1).first()
        val accessibility = services.accessibility.dump("emulator-5554")

        assertEquals(listOf("am", "start"), builtIntent.take(2))
        assertTrue("--es" in builtIntent)
        assertTrue("--ez" in builtIntent)
        assertTrue(sentIntent.isSuccess)

        assertEquals(listOf("com.disabled.app", "com.example.app", "com.android.settings"), apps.map { it.packageName })
        assertEquals("Example", apps.first { it.packageName == "com.example.app" }.label)
        assertEquals(false, apps.first { it.packageName == "com.example.app" }.system)
        assertEquals(false, apps.first { it.packageName == "com.disabled.app" }.enabled)
        assertTrue(apps.first { it.packageName == "com.android.settings" }.system)
        assertTrue(listOf(launched, stopped, cleared, reset, uninstalled).all { it.isSuccess })
        assertEquals(listOf(true, false), permissions.map { it.granted })
        assertEquals(listOf(".MainActivity", "com.example.app.SettingsActivity"), activities.map { it.name })

        assertEquals(listOf("report.txt", "traces"), files.map { it.name })
        assertEquals(128, files.first().sizeBytes)
        assertTrue(files.last().isDirectory)
        assertTrue(listOf(pulled, pushed, deleted).all { it.isSuccess })

        assertTrue(proxyConfigured.isSuccess)
        assertTrue(proxyStarted.isSuccess)
        assertTrue(proxyCleared.isSuccess)
        assertEquals("10.0.2.2", proxyHost)
        assertEquals("GET", exchange.method)
        assertEquals(201, exchange.statusCode)
        assertEquals("rule-1", exchange.matchedRuleId)
        assertEquals("application/json", exchange.responseHeaders["content-type"])
        assertTrue(env.ran("settings", "put", "global", "http_proxy", "10.0.2.2:8888"))
        assertTrue(env.ran("settings", "put", "global", "global_http_proxy_host", "10.0.2.2"))
        assertTrue(env.ran("settings", "put", "global", "global_http_proxy_port", "8888"))
        assertTrue(env.ran("settings", "delete", "global", "global_http_proxy_exclusion_list"))
        assertTrue(env.ran("settings", "delete", "global", "global_proxy_pac_url"))
        assertTrue(env.ran("settings", "put", "global", "http_proxy", ":0"))
        assertTrue(env.ran("settings", "delete", "global", "global_http_proxy_host"))
        assertTrue(env.ran("settings", "delete", "global", "global_http_proxy_port"))
        assertTrue(services.proxy.exchanges.value.isNotEmpty())
        assertTrue(services.proxy.clearTraffic().isSuccess)
        assertTrue(services.proxy.exchanges.value.isEmpty())
        assertTrue(env.proxyCommands.single().contains("--listen-port"))
        assertTrue(proxyStopped.isSuccess)

        assertEquals(14.5f, metric.cpuPercent)
        assertEquals(240f, metric.memoryMb)
        assertEquals(87, metric.batteryPercent)
        assertEquals(2, metric.processes.size)
        assertEquals(2, metric.frameRenderTimes.size)

        assertNotNull(accessibility)
        assertEquals("android.widget.FrameLayout", accessibility.className)
        assertEquals("emulator-5554 ready", accessibility.children.single().text)
        assertTrue(env.ran("uiautomator", "dump", "/sdcard/window_dump.xml"))
    }
}
