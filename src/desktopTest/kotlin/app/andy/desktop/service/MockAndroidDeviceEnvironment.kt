package app.andy.desktop.service

import app.andy.model.WorkspaceState
import app.andy.service.CommandResult
import app.andy.service.WorkspaceStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

internal class MockAndroidDeviceEnvironment {
    private val sdkRoot = Files.createTempDirectory("andy-mock-sdk").toFile()
    private val adb = executable("platform-tools/adb")
    private val emulator = executable("emulator/emulator")
    private val sdkManager = executable("cmdline-tools/latest/bin/sdkmanager")
    private val avdManager = executable("cmdline-tools/latest/bin/avdmanager")

    val store = InMemoryWorkspaceStore(WorkspaceState(selectedSdkPath = sdkRoot.absolutePath))
    val runner = CommandRunner { command, _ -> run(command) }
    val locator = SdkLocator()
    val devices = DesktopDeviceService(runner, locator, store)
    val commands = mutableListOf<List<String>>()
    val proxyCommands = mutableListOf<List<String>>()
    var rootResult: CommandResult = CommandResult.failure("adbd cannot run as root in production builds")
    var runtimeCaInjectionResult: CommandResult = CommandResult.success("Injected CA")
    var chromeFlagsResult: CommandResult = CommandResult.success()
    var remountResult: CommandResult = CommandResult.success("remount succeeded")
    var persistedCaInstalled: Boolean = false

    fun services(): TestDesktopServices {
        return TestDesktopServices(
            devices = devices,
            avd = DesktopAvdService(runner, locator) { store.load().selectedSdkPath },
            mirror = DesktopMirrorEngine(runner, devices),
            logcat = DesktopLogcatService(runner, devices),
            intents = DesktopIntentService(runner, devices),
            apps = DesktopAppService(runner, devices),
            files = DesktopFileService(runner, devices),
            proxy = DesktopProxyService(
                runner,
                devices,
                mitmdumpExecutable = { "/usr/bin/mitmdump" },
                processStarter = { command, _, _ ->
                    proxyCommands += command
                    MockProxyProcess()
                },
            ),
            metrics = DesktopMetricsService(runner, devices),
            accessibility = DesktopAccessibilityService(runner, devices),
        )
    }

    fun ran(vararg tokens: String): Boolean {
        return commands.any { command -> command.windowed(tokens.size).any { it == tokens.toList() } }
    }

    private fun executable(relativePath: String): File {
        val file = File(sdkRoot, relativePath)
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\nexit 0\n")
        file.setExecutable(true)
        return file
    }

    private fun run(command: List<String>): CommandResult {
        commands += command
        return when (command.firstOrNull()) {
            adb.absolutePath -> runAdb(command)
            sdkManager.absolutePath -> runSdkManager(command)
            avdManager.absolutePath -> runAvdManager(command)
            else -> CommandResult.failure("Unexpected command: ${command.joinToString(" ")}")
        }
    }

    private fun runAdb(command: List<String>): CommandResult {
        if (command.drop(1) == listOf("devices", "-l")) {
            return CommandResult.success(
                """
                List of devices attached
                emulator-5554	device product:sdk_gphone64_arm64 model:Pixel_8 device:emu64a transport_id:1
                R3CXB056ZZB	device product:e3q model:SM_S921U device:e3q transport_id:2
                OFFLINE	offline
                UNAUTH	unauthorized
                """.trimIndent(),
            )
        }

        val serial = command.getOrNull(2)
        return when {
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "shell" -> runShell(serial.orEmpty(), command.drop(4))
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "exec-out" -> runExecOut(serial.orEmpty(), command.drop(4))
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "logcat" -> runLogcat(command.drop(4))
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "emu" -> runEmu(serial.orEmpty(), command.drop(4))
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "root" -> rootResult
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "wait-for-device" -> CommandResult.success()
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "remount" -> remountResult
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "reboot" -> CommandResult.success()
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "uninstall" -> CommandResult.success("Success")
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "pull" -> CommandResult.success("1 file pulled")
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "push" -> CommandResult.success("1 file pushed")
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "forward" -> CommandResult.success()
            else -> CommandResult.failure("Unexpected adb command: ${command.joinToString(" ")}")
        }
    }

    private fun runEmu(serial: String, args: List<String>): CommandResult {
        return when (args) {
            listOf("kill") -> CommandResult.success("OK")
            else -> CommandResult.failure("Unexpected emu command for $serial: ${args.joinToString(" ")}")
        }
    }

    private fun runShell(serial: String, shell: List<String>): CommandResult {
        return when (shell) {
            listOf("getprop") -> CommandResult.success(getprop(serial))
            listOf("dumpsys", "battery") -> CommandResult.success("level: 87\nstatus: 2\n")
            listOf("wm", "size") -> CommandResult.success("Physical size: 1080x2400\n")
            listOf("df", "-h", "/data") -> CommandResult.success("Filesystem Size Used Avail Use% Mounted on\n/dev/block/dm-1 112G 52G 60G 47% /data\n")
            listOf("pidof", "com.example.app") -> CommandResult.success("1234 5678\n")
            listOf("cmd", "package", "list", "packages", "-U", "--show-versioncode") -> CommandResult.success(
                """
                package:com.example.app uid:10123 versionCode:42
                package:com.android.settings uid:1000 versionCode:350000
                package:com.disabled.app uid:10124 versionCode:7
                """.trimIndent(),
            )
            listOf("cmd", "package", "list", "packages", "-s") -> CommandResult.success("package:com.android.settings\n")
            listOf("cmd", "package", "list", "packages", "-d") -> CommandResult.success("package:com.disabled.app\n")
            listOf("monkey", "-p", "com.example.app", "-c", "android.intent.category.LAUNCHER", "1") -> CommandResult.success("Events injected: 1")
            listOf("am", "force-stop", "com.example.app") -> CommandResult.success()
            listOf("am", "force-stop", "com.android.chrome") -> CommandResult.success()
            listOf("sh", "/data/local/tmp/andy-inject-ca.sh") -> runtimeCaInjectionResult
            listOf("sh", "/data/local/tmp/andy-chrome-proxy-flags.sh") -> chromeFlagsResult
            listOf("pm", "clear", "com.example.app") -> CommandResult.success("Success")
            listOf("pm", "reset-permissions", "com.example.app") -> CommandResult.success()
            listOf("dumpsys", "package", "com.example.app") -> CommandResult.success(packageDump())
            listOf("ls", "-la", "/sdcard/Download/") -> CommandResult.success(fileListing())
            listOf("ls", "/system/etc/andy/cacerts/*.0") -> if (persistedCaInstalled) CommandResult.success("/system/etc/andy/cacerts/abcdef12.0") else CommandResult.failure("No such file")
            listOf("rm", "-rf", "/sdcard/Download/old.txt") -> CommandResult.success()
            listOf("mkdir", "-p", "/system/etc/andy/cacerts") -> {
                persistedCaInstalled = true
                CommandResult.success()
            }
            listOf("chmod", "644", "/system/etc/andy/cacerts/abcdef12.0") -> CommandResult.success()
            listOf("chmod", "644", "/system/etc/security/cacerts/abcdef12.0") -> CommandResult.success()
            listOf("chmod", "755", "/system/etc/andy/andy-ca-injector.sh") -> CommandResult.success()
            listOf("chmod", "644", "/system/etc/init/andy-ca.rc") -> CommandResult.success()
            listOf("chown", "root:root", "/system/etc/andy/cacerts/abcdef12.0", "/system/etc/security/cacerts/abcdef12.0", "/system/etc/andy/andy-ca-injector.sh", "/system/etc/init/andy-ca.rc") -> CommandResult.success()
            listOf("chcon", "u:object_r:system_file:s0", "/system/etc/andy/cacerts/abcdef12.0", "/system/etc/security/cacerts/abcdef12.0", "/system/etc/andy/andy-ca-injector.sh", "/system/etc/init/andy-ca.rc") -> CommandResult.success()
            listOf("test", "-f", "/system/etc/andy/andy-ca-injector.sh") -> if (persistedCaInstalled) CommandResult.success() else CommandResult.failure("missing")
            listOf("sh", "/system/etc/andy/andy-ca-injector.sh") -> runtimeCaInjectionResult
            listOf("settings", "put", "global", "http_proxy", "10.0.2.2:8888") -> CommandResult.success()
            listOf("settings", "put", "global", "http_proxy", ":0") -> CommandResult.success()
            listOf("settings", "put", "global", "global_http_proxy_host", "10.0.2.2") -> CommandResult.success()
            listOf("settings", "put", "global", "global_http_proxy_port", "8888") -> CommandResult.success()
            listOf("settings", "delete", "global", "global_http_proxy_host") -> CommandResult.success()
            listOf("settings", "delete", "global", "global_http_proxy_port") -> CommandResult.success()
            listOf("settings", "delete", "global", "global_http_proxy_exclusion_list") -> CommandResult.success()
            listOf("settings", "delete", "global", "global_proxy_pac_url") -> CommandResult.success()
            listOf("dumpsys", "cpuinfo") -> CommandResult.success("14.5% 1234/com.example.app: 8.2% user + 6.3% kernel\n")
            listOf("top", "-b", "-n", "1", "-o", "PID,%CPU,RES,ARGS", "-m", "80") -> CommandResult.success(
                """
                PID %CPU RES ARGS
                1234 12.5 169M com.example.app
                2222 1.0 24M com.android.systemui
                """.trimIndent(),
            )
            listOf("dumpsys", "meminfo", "com.example.app") -> CommandResult.success("TOTAL 245760\n")
            listOf("dumpsys", "window", "windows") -> CommandResult.success("mCurrentFocus=Window{abc u0 com.example.app/.MainActivity}\n")
            listOf("dumpsys", "activity", "activities") -> CommandResult.success("topResumedActivity=ActivityRecord{abc u0 com.example.app/.MainActivity t1}\n")
            listOf("dumpsys", "gfxinfo", "com.example.app", "framestats") -> CommandResult.success(frameStats())
            listOf("uiautomator", "dump", "/sdcard/window_dump.xml") -> CommandResult.success("UI hierchary dumped to: /sdcard/window_dump.xml")
            listOf("input", "tap", "44", "55") -> CommandResult.success()
            listOf("input", "swipe", "1", "2", "3", "4", "250") -> CommandResult.success()
            listOf("input", "text", "hello%sworld") -> CommandResult.success()
            listOf("input", "keyevent", "4") -> CommandResult.success()
            else -> {
                if (shell.take(2) == listOf("am", "start")) {
                    CommandResult.success("Starting: Intent")
                } else {
                    CommandResult.failure("Unexpected shell command for $serial: ${shell.joinToString(" ")}")
                }
            }
        }
    }

    private fun runExecOut(serial: String, exec: List<String>): CommandResult {
        return when (exec) {
            listOf("screencap", "-p") -> CommandResult.success("PNGDATA")
            listOf("cat", "/sdcard/window_dump.xml") -> CommandResult.success(accessibilityXml(serial))
            else -> CommandResult.failure("Unexpected exec-out command: ${exec.joinToString(" ")}")
        }
    }

    private fun runLogcat(args: List<String>): CommandResult {
        check(args.take(6) == listOf("-d", "-v", "threadtime", "-t", "50")) {
            "Unexpected logcat args: ${args.joinToString(" ")}"
        }
        return CommandResult.success(
            """
            07-07 09:36:39.683 1234 1234 D Example: warm start complete
            07-07 09:36:40.000 9999 9999 E Other: should be filtered
            07-07 09:36:41.100 5678 5678 W Example: network failure
            """.trimIndent(),
        )
    }

    private fun runSdkManager(command: List<String>): CommandResult {
        return when (command.drop(1)) {
            listOf("--list_installed") -> CommandResult.success("system-images;android-36;google_apis;arm64-v8a | 7 | Google APIs ARM 64 v8a System Image | Installed")
            listOf("--list") -> CommandResult.success("system-images;android-35;google_apis_playstore;arm64-v8a | 6 | Google Play ARM 64 v8a System Image | Available")
            else -> CommandResult.failure("Unexpected sdkmanager command: ${command.joinToString(" ")}")
        }
    }

    private fun runAvdManager(command: List<String>): CommandResult {
        return when {
            command.drop(1) == listOf("list", "device") -> CommandResult.success(
                """
                id: 34 or "pixel_8"
                    Name: Pixel 8
                    OEM : Google
                    Screen: 1080 x 2400
                    dpis : 420
                """.trimIndent(),
            )
            command.drop(1) == listOf("list", "avd") -> CommandResult.success(
                """
                Name: Pixel_8_API_36
                Path: /Users/test/.android/avd/Pixel_8_API_36.avd
                Target: Google APIs (Google Inc.)
                ABI: arm64-v8a
                """.trimIndent(),
            )
            command.drop(1).take(4) == listOf("create", "avd", "-n", "Pixel_8_API_36") -> CommandResult.success("Created AVD")
            else -> CommandResult.failure("Unexpected avdmanager command: ${command.joinToString(" ")}")
        }
    }

    private fun getprop(serial: String): String {
        return if (serial.startsWith("emulator-")) {
            """
            [ro.boot.qemu.avd_name]: [Pixel_8_API_36]
            [ro.build.version.sdk]: [36]
            [ro.product.cpu.abi]: [arm64-v8a]
            [ro.product.model]: [sdk_gphone64_arm64]
            [ro.product.name]: [sdk_gphone64_arm64]
            """.trimIndent()
        } else {
            """
            [ro.build.version.sdk]: [35]
            [ro.product.cpu.abi]: [arm64-v8a]
            [ro.product.model]: [Galaxy S24]
            [ro.product.name]: [e3q]
            """.trimIndent()
        }
    }

    private fun packageDump() = """
        requested permissions:
          android.permission.CAMERA
          android.permission.POST_NOTIFICATIONS
        runtime permissions:
          android.permission.CAMERA: granted=true, flags=[ USER_SET]
          android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET]
        Activity Resolver Table:
          com.example.app/.MainActivity filter 123
          com.example.app/com.example.app.SettingsActivity filter 456
    """.trimIndent()

    private fun fileListing() = """
        drwxrwx--- 2 u0_a123 sdcard_rw 4096 2026-07-07 10:00 .
        drwxrwx--- 7 u0_a123 sdcard_rw 4096 2026-07-07 09:59 ..
        -rw-rw---- 1 u0_a123 sdcard_rw 128 2026-07-07 10:00 report.txt
        drwxrwx--- 2 u0_a123 sdcard_rw 4096 2026-07-07 10:01 traces
    """.trimIndent()

    private fun frameStats() = """
        Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted
        0,1000000000,1000000000,0,0,0,0,0,0,0,0,0,0,1010000000,0,0,0
        0,2000000000,2000000000,0,0,0,0,0,0,0,0,0,0,2025000000,0,0,0
    """.trimIndent()

    private fun accessibilityXml(serial: String) = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="root" class="android.widget.FrameLayout" package="com.example" bounds="[0,0][1080,2400]" clickable="false" focusable="false" enabled="true" selected="false" scrollable="false">
            <node index="0" text="$serial ready" content-desc="Ready" resource-id="com.example:id/ready" class="android.widget.TextView" package="com.example" bounds="[32,100][240,160]" clickable="true" focusable="true" enabled="true" />
          </node>
        </hierarchy>
    """.trimIndent()
}

internal class MockProxyProcess : ProxyProcess {
    override val stdout: InputStream = ByteArrayInputStream(
        """
        {"type":"flow","id":"flow-1","startedAtMillis":1000,"completedAtMillis":1042,"durationMillis":42,"method":"GET","url":"https://example.test/api","statusCode":201,"contentType":"application/json","sizeBytes":17,"requestHeaders":{"accept":"application/json"},"responseHeaders":{"content-type":"application/json"},"requestBodyPreview":null,"responseBodyPreview":"{\"ok\":true}","error":null,"tlsStatus":"tls","matchedRuleId":"rule-1"}
        """.trimIndent().encodeToByteArray(),
    )
    override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
    private var alive = true
    override fun isAlive(): Boolean = alive
    override fun destroy() {
        alive = false
    }
}

internal data class TestDesktopServices(
    val devices: DesktopDeviceService,
    val avd: DesktopAvdService,
    val mirror: DesktopMirrorEngine,
    val logcat: DesktopLogcatService,
    val intents: DesktopIntentService,
    val apps: DesktopAppService,
    val files: DesktopFileService,
    val proxy: DesktopProxyService,
    val metrics: DesktopMetricsService,
    val accessibility: DesktopAccessibilityService,
)

internal class InMemoryWorkspaceStore(initialState: WorkspaceState = WorkspaceState()) : WorkspaceStore {
    private var state = initialState

    override suspend fun load(): WorkspaceState = state

    override suspend fun save(state: WorkspaceState) {
        this.state = state
    }
}
