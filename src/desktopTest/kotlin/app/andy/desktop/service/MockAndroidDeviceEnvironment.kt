package app.andy.desktop.service

import app.andy.desktop.service.mirror.DesktopMirrorEngine
import app.andy.desktop.service.proxy.DesktopProxyService
import app.andy.desktop.service.proxy.ProxyProcess
import app.andy.model.WorkspaceState
import app.andy.service.CommandResult
import app.andy.service.WorkspaceStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

internal class MockAndroidDeviceEnvironment {
    private val sdkRoot = Files.createTempDirectory("andy-mock-sdk").toFile()
    private val adb = executable("platform-tools/${toolName("adb", windowsExtension = "exe")}")
    private val emulator = executable("emulator/${toolName("emulator", windowsExtension = "bat")}")
    private val sdkManager = executable("cmdline-tools/latest/bin/${toolName("sdkmanager", windowsExtension = "bat")}")
    private val avdManager = executable("cmdline-tools/latest/bin/${toolName("avdmanager", windowsExtension = "bat")}")
    private val avdHome = File(sdkRoot, "avd")

    val store = InMemoryWorkspaceStore(WorkspaceState(selectedSdkPath = sdkRoot.absolutePath))
    val runner = CommandRunner { command, _ -> run(command) }
    val locator = SdkLocator()
    val devices = DesktopDeviceService(runner, locator, store)
    val commands = mutableListOf<List<String>>()
    val proxyCommands = mutableListOf<List<String>>()
    var adbDevicesOutput: String = """
        List of devices attached
        emulator-5554	device product:sdk_gphone64_arm64 model:Pixel_8 device:emu64a transport_id:1
        R3CXB056ZZB	device product:e3q model:SM_S921U device:e3q transport_id:2
        OFFLINE	offline
        UNAUTH	unauthorized
    """.trimIndent()
    var mdnsServicesOutput: String = """
        List of discovered mdns services
        adb-VAN10A203710441	_adb._tcp	192.168.86.47:5555
        adb-VAN10A203710441	_adb-tls-connect._tcp	192.168.86.47:37123
        adb-PAIRING	_adb-tls-pairing._tcp	192.168.86.47:37199
    """.trimIndent()
    var pairShouldSucceed: Boolean = true
    var connectShouldSucceed: Boolean = true
    var rootResult: CommandResult = CommandResult.failure("adbd cannot run as root in production builds")
    var runtimeCaInjectionResult: CommandResult = CommandResult.success("Injected CA")
    var chromeFlagsResult: CommandResult = CommandResult.success()
    var remountResult: CommandResult = CommandResult.success("remount succeeded")
    var wifiEnabled: Boolean = true
    var wifiStatusCommandSupported: Boolean = true
    var persistedCaInstalled: Boolean = false
    var keepStoppedEmulatorInAdbAsOffline: Boolean = false
    var httpProxyValue: String = "10.0.2.2:8888"
    var connectivityDump: String = "NetworkAgentInfo [WIFI () - 100]\n"
    var routeToProxyOutput: String = "10.0.2.2 dev eth0 src 10.0.2.15\n"
    var macProxyOutput: String = """
        <dictionary> {
          ExcludeSimpleHostnames : 1
        }
    """.trimIndent()
    var hostIfconfigOutput: String = "lo0: flags=8049<UP,LOOPBACK,RUNNING,MULTICAST>\n"
    var hostOsName: String = "Mac OS X"
    var getpropOverrides: Map<String, String> = emptyMap()
    /** Single-key `getprop <key>` overrides used by tracing and similar callers. */
    var propValues: MutableMap<String, String> = mutableMapOf(
        "ro.build.version.sdk" to "35",
        "init.svc.traced" to "running",
        "persist.traced.enable" to "1",
    )
    var perfettoBackgroundResult: CommandResult = CommandResult.success("12345")
    var perfettoAliveChecksRemaining: Int = 2
    val perfettoConfigs: MutableList<String> = mutableListOf()
    var pullWritesNonEmptyFile: Boolean = true
    val installedPackages: MutableSet<String> = mutableSetOf()

    init {
        File(avdHome, "Pixel_8_API_36.avd").apply {
            mkdirs()
            resolve("config.ini").writeText(
                """
                AvdId=Pixel_8_API_36
                avd.ini.displayname=Pixel_8_API_36
                abi.type=arm64-v8a
                image.sysdir.1=system-images/android-36/google_apis/arm64-v8a/
                hw.device.name=Pixel 8
                """.trimIndent(),
            )
            resolve("snapshots/default_boot").mkdirs()
        }
        File(avdHome, "Pixel_8_API_36.ini").writeText(
            """
            avd.ini.encoding=UTF-8
            path=${File(avdHome, "Pixel_8_API_36.avd").absolutePath}
            path.rel=avd/Pixel_8_API_36.avd
            target=android-36
            """.trimIndent(),
        )
    }

    fun services(): TestDesktopServices {
        return TestDesktopServices(
            devices = devices,
            avd = DesktopAvdService(
                runner,
                locator,
                listAvdsFromDisk = {
                    AvdHomeScanner.listVirtualDevices(env = mapOf("ANDROID_AVD_HOME" to avdHome.absolutePath))
                },
                resolveAvdHome = { avdHome },
            ) { store.load().selectedSdkPath },
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
                hostOsName = { hostOsName },
            ),
            metrics = DesktopMetricsService(runner, devices),
            accessibility = DesktopAccessibilityService(runner, devices),
            bugs = DesktopBugService(DesktopMirrorEngine(runner, devices), DesktopLogcatService(runner, devices), sdkRoot),
            artifacts = DesktopArtifactService(runner, devices, DesktopMirrorEngine(runner, devices)),
        )
    }

    fun ran(vararg tokens: String): Boolean {
        return commands.any { command -> command.windowed(tokens.size).any { it == tokens.toList() } }
    }

    fun snapshotPath(name: String): File {
        return File(avdHome, "Pixel_8_API_36.avd/snapshots/$name")
    }

    fun hardwareQemuLockPath(name: String = "Pixel_8_API_36"): File {
        return File(avdHome, "$name.avd/hardware-qemu.ini.lock")
    }

    private fun toolName(name: String, windowsExtension: String): String {
        val os = System.getProperty("os.name")
        return if (os.startsWith("Windows", ignoreCase = true)) "$name.$windowsExtension" else name
    }

    private fun executable(relativePath: String): File {
        val file = File(sdkRoot, relativePath)
        file.parentFile.mkdirs()
        if (file.extension.equals("bat", ignoreCase = true)) {
            file.writeText("@echo off\r\nexit /b 0\r\n")
        } else {
            file.writeText("#!/bin/sh\nexit 0\n")
        }
        file.setExecutable(true)
        return file
    }

    var prefsXmlByFile = mutableMapOf(
        "demo.xml" to """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="greeting">hello</string>
                <int name="count" value="1" />
            </map>
        """.trimIndent(),
    )
    var databaseListing = "app.db\napp.db-wal\n"
    var noBackupListing: String? = null
    private val pushedTmpFiles = mutableMapOf<String, String>()

    private fun run(command: List<String>): CommandResult {
        commands += command
        return when (command.firstOrNull()) {
            adb.absolutePath -> runAdb(command)
            sdkManager.absolutePath -> runSdkManager(command)
            avdManager.absolutePath -> runAvdManager(command)
            "/usr/sbin/scutil" -> if (command == listOf("/usr/sbin/scutil", "--proxy")) CommandResult.success(macProxyOutput) else CommandResult.failure("Unexpected scutil command: ${command.joinToString(" ")}")
            "/sbin/ifconfig" -> if (command == listOf("/sbin/ifconfig")) CommandResult.success(hostIfconfigOutput) else CommandResult.failure("Unexpected ifconfig command: ${command.joinToString(" ")}")
            else -> CommandResult.failure("Unexpected command: ${command.joinToString(" ")}")
        }
    }

    private fun runAdb(command: List<String>): CommandResult {
        if (command.drop(1) == listOf("devices", "-l")) {
            return CommandResult.success(adbDevicesOutput)
        }
        if (command.drop(1) == listOf("mdns", "check")) {
            return CommandResult.success("mdns daemon version ...")
        }
        if (command.drop(1) == listOf("mdns", "services")) {
            return CommandResult.success(mdnsServicesOutput)
        }
        if (command.getOrNull(1) == "pair") {
            val endpoint = command.getOrNull(2).orEmpty()
            val code = command.getOrNull(3).orEmpty()
            return if (pairShouldSucceed && code.isNotBlank()) {
                CommandResult.success("Successfully paired to $endpoint [guid=mock-guid]")
            } else {
                CommandResult(0, "Failed: Wrong password or connection was dropped.", "")
            }
        }
        if (command.getOrNull(1) == "connect") {
            val endpoint = command.getOrNull(2).orEmpty()
            return if (connectShouldSucceed) {
                if (!adbDevicesOutput.contains(endpoint)) {
                    adbDevicesOutput = adbDevicesOutput.trimEnd() + "\n$endpoint\tdevice product:wifi model:Wifi_Device device:wifi transport_id:9"
                }
                CommandResult.success("connected to $endpoint")
            } else {
                CommandResult(0, "failed to connect to $endpoint", "")
            }
        }
        if (command.getOrNull(1) == "disconnect") {
            val serial = command.getOrNull(2).orEmpty()
            adbDevicesOutput = adbDevicesOutput.lineSequence()
                .filterNot { it.trimStart().startsWith("$serial\t") || it.trimStart().startsWith("$serial ") }
                .joinToString("\n")
            return CommandResult.success("disconnected $serial")
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
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "install" -> runInstall(command.drop(4))
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "pull" -> {
                val remote = command.getOrNull(4)
                val local = command.getOrNull(5)
                if (local != null && pullWritesNonEmptyFile) {
                    File(local).apply {
                        parentFile?.mkdirs()
                        writeBytes(byteArrayOf(1, 2, 3, 4, 5))
                    }
                } else if (local != null) {
                    File(local).apply {
                        parentFile?.mkdirs()
                        writeBytes(byteArrayOf())
                    }
                }
                CommandResult.success("1 file pulled, remote=$remote")
            }
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "push" -> {
                val local = command.getOrNull(4)
                val remote = command.getOrNull(5)
                if (local != null && remote != null) {
                    pushedTmpFiles[remote] = File(local).readText()
                }
                CommandResult.success("1 file pushed")
            }
            command.getOrNull(1) == "-s" && command.getOrNull(3) == "forward" -> CommandResult.success()
            else -> CommandResult.failure("Unexpected adb command: ${command.joinToString(" ")}")
        }
    }

    private fun runInstall(args: List<String>): CommandResult {
        val replace = args.firstOrNull() == "-r"
        val apkPath = if (replace) args.getOrNull(1) else args.firstOrNull()
        if (apkPath.isNullOrBlank()) {
            return CommandResult.failure("No APK path supplied")
        }
        if (!replace && installedPackages.contains(apkPath)) {
            return CommandResult.failure("Failure [INSTALL_FAILED_ALREADY_EXISTS]")
        }
        installedPackages.add(apkPath)
        return CommandResult.success("Success")
    }

    private fun runEmu(serial: String, args: List<String>): CommandResult {
        return when (args) {
            listOf("kill") -> {
                // Most kills remove the emulator from adb immediately; some leave a stale offline serial briefly.
                adbDevicesOutput = adbDevicesOutput.lineSequence()
                    .mapNotNull { line ->
                        if (!line.trimStart().startsWith("$serial\t")) return@mapNotNull line
                        if (keepStoppedEmulatorInAdbAsOffline) "$serial\toffline" else null
                    }
                    .joinToString("\n")
                CommandResult.success("OK")
            }
            listOf("avd", "snapshot", "list") -> CommandResult.success("default_boot\nmanual\n")
            listOf("avd", "snapshot", "save", "manual") -> CommandResult.success("OK")
            listOf("avd", "snapshot", "load", "manual") -> CommandResult.success("OK")
            listOf("avd", "snapshot", "delete", "manual") -> CommandResult.success("OK")
            else -> CommandResult.failure("Unexpected emu command for $serial: ${args.joinToString(" ")}")
        }
    }

    private fun runShell(serial: String, shell: List<String>): CommandResult {
        if (shell.firstOrNull() == "run-as") {
            return runAs(shell.drop(1))
        }
        if (shell.size == 5 && shell.take(4) == listOf("settings", "put", "global", "http_proxy")) {
            httpProxyValue = shell[4]
            return CommandResult.success()
        }
        if (shell == listOf("settings", "get", "global", "http_proxy")) {
            return CommandResult.success(httpProxyValue)
        }
        if (shell.size == 5 && shell.take(4) == listOf("settings", "put", "global", "global_http_proxy_host")) {
            return CommandResult.success()
        }
        if (shell.size == 5 && shell.take(4) == listOf("settings", "put", "global", "global_http_proxy_port")) {
            return CommandResult.success()
        }
        if (shell.size == 4 && shell.take(3) == listOf("settings", "delete", "global")) {
            return CommandResult.success()
        }
        if (shell == listOf("dumpsys", "connectivity")) {
            return CommandResult.success(connectivityDump)
        }
        if (shell.size == 4 && shell.take(3) == listOf("ip", "route", "get")) {
            return CommandResult.success(routeToProxyOutput)
        }
        if (shell == listOf("cmd", "wifi", "status")) {
            if (!wifiStatusCommandSupported) return CommandResult.failure("Unknown command: status")
            return CommandResult.success(if (wifiEnabled) "Wifi is enabled\nWifi is connected to \"AndroidWifi\"" else "Wifi is disabled")
        }
        if (shell == listOf("settings", "get", "global", "wifi_on")) {
            return CommandResult.success(if (wifiEnabled) "1" else "0")
        }
        if (shell == listOf("cmd", "wifi", "set-wifi-enabled", "disabled") || shell == listOf("svc", "wifi", "disable")) {
            wifiEnabled = false
            return CommandResult.success()
        }
        if (shell == listOf("cmd", "wifi", "set-wifi-enabled", "enabled") || shell == listOf("svc", "wifi", "enable")) {
            wifiEnabled = true
            return CommandResult.success()
        }
        if (shell == listOf("cmd", "wifi", "reconnect") || shell == listOf("cmd", "wifi", "start-scan")) {
            return CommandResult.success()
        }
        if (shell == listOf("svc", "data", "disable") || shell == listOf("svc", "data", "enable")) {
            return CommandResult.success()
        }
        if (shell.size == 2 && shell[0] == "getprop") {
            val key = shell[1]
            return CommandResult.success(propValues[key].orEmpty())
        }
        if (shell.size == 3 && shell[0] == "setprop") {
            propValues[shell[1]] = shell[2]
            return CommandResult.success()
        }
        if (shell.size == 3 && shell[0] == "test" && shell[1] == "-d" && shell[2].startsWith("/proc/")) {
            return if (perfettoAliveChecksRemaining > 0) {
                perfettoAliveChecksRemaining--
                CommandResult.success()
            } else {
                CommandResult.failure("No such process")
            }
        }
        if (shell.size == 3 && shell[0] == "kill") {
            val signal = shell[1]
            val pid = shell[2]
            return when (signal) {
                "-0" -> {
                    if (perfettoAliveChecksRemaining > 0) {
                        perfettoAliveChecksRemaining--
                        CommandResult.success()
                    } else {
                        CommandResult.failure("No such process")
                    }
                }
                "-TERM", "-KILL" -> {
                    perfettoAliveChecksRemaining = 0
                    CommandResult.success("killed $pid")
                }
                else -> CommandResult.failure("Unexpected kill signal $signal")
            }
        }
        if (shell.size == 2 && shell[0] == "rm") {
            return CommandResult.success()
        }
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
            listOf("dumpsys", "package") -> CommandResult.success(allPackageDump())
            listOf("monkey", "-p", "com.example.app", "-c", "android.intent.category.LAUNCHER", "1") -> CommandResult.success("Events injected: 1")
            listOf("am", "force-stop", "com.example.app") -> CommandResult.success()
            listOf("am", "force-stop", "com.android.chrome") -> CommandResult.success()
            listOf("am", "start", "-a", "android.settings.SECURITY_SETTINGS") -> CommandResult.success("Starting: Intent")
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
            listOf("am", "start", "-a", "android.settings.VPN_SETTINGS") -> CommandResult.success("Starting: Intent")
            else -> {
                if (shell.take(2) == listOf("am", "start")) {
                    CommandResult.success("Starting: Intent")
                } else {
                    CommandResult.failure("Unexpected shell command for $serial: ${shell.joinToString(" ")}")
                }
            }
        }
    }

    private fun runAs(args: List<String>): CommandResult {
        val packageName = args.firstOrNull() ?: return CommandResult.failure("missing package")
        if (packageName == "com.undebuggable.app") {
            return CommandResult.failure("run-as: Package '$packageName' is not debuggable")
        }
        val rest = args.drop(1)
        return when {
            rest == listOf("id") -> CommandResult.success("uid=10123(u0_a123) gid=10123(u0_a123)")
            rest == listOf("ls", "shared_prefs") ->
                CommandResult.success(prefsXmlByFile.keys.sorted().joinToString("\n", postfix = "\n"))
            rest.size == 2 && rest[0] == "cat" && rest[1].startsWith("shared_prefs/") -> {
                val name = rest[1].removePrefix("shared_prefs/")
                prefsXmlByFile[name]?.let { CommandResult.success(it) }
                    ?: CommandResult.failure("No such file")
            }
            rest == listOf("ls", "databases") -> CommandResult.success(databaseListing)
            rest == listOf("ls", "no_backup") -> {
                if (noBackupListing == null) {
                    CommandResult.failure("ls: no_backup: No such file or directory")
                } else {
                    CommandResult.success(noBackupListing.orEmpty())
                }
            }
            rest.size == 3 && rest[0] == "cp" -> {
                val remote = rest[1]
                val dest = rest[2]
                when {
                    dest.startsWith("shared_prefs/") -> {
                        val name = dest.removePrefix("shared_prefs/")
                        val content = pushedTmpFiles[remote] ?: return CommandResult.failure("missing tmp")
                        prefsXmlByFile[name] = content
                        CommandResult.success()
                    }
                    dest.contains("databases/") || dest.startsWith("no_backup/") -> CommandResult.success()
                    else -> CommandResult.failure("Unexpected run-as cp: ${rest.joinToString(" ")}")
                }
            }
            rest.size >= 2 && rest[0] == "rm" -> CommandResult.success()
            rest.size == 3 && rest[0] == "sh" && rest[1] == "-c" -> {
                val script = rest[2]
                val cpMatch = Regex("""cp '([^']+)' 'shared_prefs/([^']+)'""").find(script)
                if (cpMatch != null) {
                    val remote = cpMatch.groupValues[1]
                    val name = cpMatch.groupValues[2]
                    val content = pushedTmpFiles[remote] ?: return CommandResult.failure("missing tmp")
                    prefsXmlByFile[name] = content
                    return CommandResult.success()
                }
                if (script.startsWith("test -f ")) {
                    return CommandResult.success("no")
                }
                if (script.startsWith("rm -f ")) {
                    return CommandResult.success()
                }
                if (script.startsWith("cp '") && script.contains("'databases/")) {
                    return CommandResult.success()
                }
                CommandResult.failure("Unexpected run-as sh: $script")
            }
            else -> CommandResult.failure("Unexpected run-as command: ${args.joinToString(" ")}")
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
            listOf("--install", "system-images;android-35;google_apis_playstore;arm64-v8a") -> CommandResult.success("Installed")
            listOf("--uninstall", "system-images;android-36;google_apis;arm64-v8a") -> CommandResult.success("Uninstalled")
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
                Path: ${File(avdHome, "Pixel_8_API_36.avd").absolutePath}
                Target: Google APIs (Google Inc.)
                ABI: arm64-v8a
                API level: 36
                """.trimIndent(),
            )
            command.drop(1).take(4) == listOf("create", "avd", "-n", "Pixel_8_API_36") -> CommandResult.success("Created AVD")
            command.drop(1) == listOf("delete", "avd", "-n", "Pixel_8_API_36") -> CommandResult.success("Deleted AVD")
            else -> CommandResult.failure("Unexpected avdmanager command: ${command.joinToString(" ")}")
        }
    }

    private fun getprop(serial: String): String {
        getpropOverrides[serial]?.let { return it }
        return if (serial.startsWith("emulator-")) {
            """
            [ro.boot.qemu.avd_name]: [Pixel_8_API_36]
            [ro.build.version.sdk]: [36]
            [ro.product.cpu.abi]: [arm64-v8a]
            [ro.product.model]: [sdk_gphone64_arm64]
            [ro.product.name]: [sdk_gphone64_arm64]
            [ro.serialno]: [$serial]
            """.trimIndent()
        } else {
            val hardwareId = when {
                serial.startsWith("adb-") -> serial.substringAfter("adb-").substringBefore("._").substringBefore('-').ifBlank { serial }
                serial.contains(':') -> "WIFI-${serial.substringBefore(':').replace('.', '-')}"
                else -> serial
            }
            """
            [ro.build.version.sdk]: [35]
            [ro.product.cpu.abi]: [arm64-v8a]
            [ro.product.model]: [Galaxy S24]
            [ro.product.name]: [e3q]
            [ro.serialno]: [$hardwareId]
            """.trimIndent()
        }
    }

    private fun packageDump() = """
        versionName=2026.0709.1406-debug
        versionCode=394 minSdk=26 targetSdk=36
        signatures=PackageSignatures{abc version:2, signatures:[abcdef]}
        pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA DEBUGGABLE ]
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

    private fun allPackageDump() = """
        Package [com.example.app] (abc):
          application-label:'Example'
        Package [com.android.settings] (def):
          application-label:'Settings'
        Package [com.disabled.app] (ghi):
          application-label:'Disabled'
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

internal class MockProxyProcess(
    stdoutText: String = """
        {"type":"flow","id":"flow-1","startedAtMillis":1000,"completedAtMillis":1042,"durationMillis":42,"method":"GET","url":"https://example.test/api","statusCode":201,"contentType":"application/json","sizeBytes":17,"requestHeaders":{"accept":"application/json"},"responseHeaders":{"content-type":"application/json"},"requestBodyPreview":null,"responseBodyPreview":"{\"ok\":true}","error":null,"tlsStatus":"tls","matchedRuleId":"rule-1"}
        """.trimIndent(),
    stderrText: String = "",
) : ProxyProcess {
    override val stdout: InputStream = ByteArrayInputStream(stdoutText.encodeToByteArray())
    override val stderr: InputStream = ByteArrayInputStream(stderrText.encodeToByteArray())
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
    val bugs: DesktopBugService,
    val artifacts: DesktopArtifactService,
)

internal class InMemoryWorkspaceStore(initialState: WorkspaceState = WorkspaceState()) : WorkspaceStore {
    private var state = initialState

    override suspend fun load(): WorkspaceState = state

    override suspend fun save(state: WorkspaceState) {
        this.state = state
    }
}
